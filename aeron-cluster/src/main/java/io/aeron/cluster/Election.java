/*
 * Copyright 2014-2018 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.codecs.RecordingLogDecoder;
import io.aeron.cluster.codecs.RecoveryPlanDecoder;
import io.aeron.cluster.service.Cluster;
import org.agrona.CloseHelper;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static io.aeron.archive.client.AeronArchive.NULL_POSITION;

/**
 * Election process to determine a new cluster leader.
 */
class Election implements MemberStatusListener, AutoCloseable
{
    static final int ELECTION_STATE_TYPE_ID = 207;

    enum State
    {
        INIT(0),

        CANVASS(1),

        NOMINATE(2),

        CANDIDATE_BALLOT(3),

        FOLLOWER_BALLOT(4),

        LEADER_TRANSITION(5),

        LEADER_READY(6),

        FOLLOWER_CATCHUP(7)
        {
            void exit(final Election election)
            {
                election.closeCatchUp();
            }
        },

        FOLLOWER_TRANSITION(8),

        FOLLOWER_READY(9);

        static final State[] STATES;

        static
        {
            final State[] states = values();
            STATES = new State[states.length];
            for (final State state : states)
            {
                final int code = state.code();
                if (null != STATES[code])
                {
                    throw new IllegalStateException("code already in use: " + code);
                }

                STATES[code] = state;
            }
        }

        private final int code;

        State(final int code)
        {
            this.code = code;
        }

        void exit(final Election election)
        {
        }

        int code()
        {
            return code;
        }

        static State get(final int code)
        {
            if (code < 0 || code > (STATES.length - 1))
            {
                throw new IllegalStateException("invalid state counter code: " + code);
            }

            return STATES[code];
        }
    }

    private final boolean isStartup;
    private final long statusIntervalMs;
    private final long leaderHeartbeatIntervalMs;
    private final ClusterMember[] clusterMembers;
    private final ClusterMember thisMember;
    private final MemberStatusAdapter memberStatusAdapter;
    private final MemberStatusPublisher memberStatusPublisher;
    private final ConsensusModule.Context ctx;
    private final RecordingLog.RecoveryPlan recoveryPlan;
    private final AeronArchive localArchive;
    private final SequencerAgent sequencerAgent;
    private final Random random;

    private long logPosition;
    private long timeOfLastStateChangeMs;
    private long timeOfLastUpdateMs;
    private long nominationDeadlineMs;
    private long leadershipTermId;
    private int logSessionId = CommonContext.NULL_SESSION_ID;
    private ClusterMember leaderMember = null;
    private State state = State.INIT;
    private Counter stateCounter;
    private LogCatchup logCatchup;

    Election(
        final boolean isStartup,
        final long leadershipTermId,
        final ClusterMember[] clusterMembers,
        final ClusterMember thisMember,
        final MemberStatusAdapter memberStatusAdapter,
        final MemberStatusPublisher memberStatusPublisher,
        final RecordingLog.RecoveryPlan recoveryPlan,
        final ConsensusModule.Context ctx,
        final AeronArchive localArchive,
        final SequencerAgent sequencerAgent)
    {
        this.isStartup = isStartup;
        this.statusIntervalMs = TimeUnit.NANOSECONDS.toMillis(ctx.statusIntervalNs());
        this.leaderHeartbeatIntervalMs = TimeUnit.NANOSECONDS.toMillis(ctx.leaderHeartbeatIntervalNs());
        this.leadershipTermId = leadershipTermId;
        this.clusterMembers = clusterMembers;
        this.thisMember = thisMember;
        this.memberStatusAdapter = memberStatusAdapter;
        this.memberStatusPublisher = memberStatusPublisher;
        this.recoveryPlan = recoveryPlan;
        this.ctx = ctx;
        this.localArchive = localArchive;
        this.sequencerAgent = sequencerAgent;
        this.random = ctx.random();
        logPosition = recoveryPlan.lastAppendedLogPosition;
    }

    public void close()
    {
        CloseHelper.close(logCatchup);
        CloseHelper.close(stateCounter);
    }

    public int doWork(final long nowMs)
    {
        int workCount = State.INIT == state ? init(nowMs) : 0;
        workCount += memberStatusAdapter.poll();

        switch (state)
        {
            case CANVASS:
                workCount += canvass(nowMs);
                break;

            case NOMINATE:
                workCount += nominate(nowMs);
                break;

            case CANDIDATE_BALLOT:
                workCount += candidateBallot(nowMs);
                break;

            case FOLLOWER_BALLOT:
                workCount += followerBallot(nowMs);
                break;

            case LEADER_TRANSITION:
                workCount += leaderTransition(nowMs);
                break;

            case LEADER_READY:
                workCount += leaderReady(nowMs);
                break;

            case FOLLOWER_CATCHUP:
                workCount += followerCatchup(nowMs);
                break;

            case FOLLOWER_TRANSITION:
                workCount += followerTransition(nowMs);
                break;

            case FOLLOWER_READY:
                workCount += followerReady(nowMs);
                break;
        }

        return workCount;
    }

    public void onCanvassPosition(final long logPosition, final long leadershipTermId, final int followerMemberId)
    {
        clusterMembers[followerMemberId]
            .logPosition(logPosition)
            .leadershipTermId(leadershipTermId);

        if (State.LEADER_READY == state && leadershipTermId <= this.leadershipTermId)
        {
            memberStatusPublisher.newLeadershipTerm(
                clusterMembers[followerMemberId].publication(),
                logPosition,
                leadershipTermId,
                thisMember.id(),
                logSessionId);
        }
        else if (State.CANVASS != state && leadershipTermId > this.leadershipTermId)
        {
            state(State.CANVASS, ctx.epochClock().time());
        }
    }

    public void onRequestVote(final long logPosition, final long candidateTermId, final int candidateId)
    {
        if (candidateTermId <= leadershipTermId)
        {
            placeVote(candidateTermId, candidateId, false);
        }
        else if (candidateTermId == leadershipTermId + 1 && logPosition < this.logPosition)
        {
            placeVote(candidateTermId, candidateId, false);

            leadershipTermId = candidateTermId;
            final long nowMs = ctx.epochClock().time();
            ctx.recordingLog().appendTerm(candidateTermId, this.logPosition, nowMs);
            state(State.CANVASS, nowMs);
        }
        else
        {
            leadershipTermId = candidateTermId;
            final long nowMs = ctx.epochClock().time();
            ctx.recordingLog().appendTerm(candidateTermId, logPosition, nowMs);
            state(State.FOLLOWER_BALLOT, nowMs);

            placeVote(candidateTermId, candidateId, true);
        }
    }

    public void onVote(
        final long candidateTermId, final int candidateMemberId, final int followerMemberId, final boolean vote)
    {
        if (Cluster.Role.CANDIDATE == sequencerAgent.role() &&
            candidateTermId == leadershipTermId &&
            candidateMemberId == thisMember.id())
        {
            clusterMembers[followerMemberId]
                .leadershipTermId(candidateTermId)
                .votedFor(vote ? Boolean.TRUE : Boolean.FALSE);
        }
    }

    public void onNewLeadershipTerm(
        final long logPosition, final long leadershipTermId, final int leaderMemberId, final int logSessionId)
    {
        if ((State.FOLLOWER_BALLOT == state || State.CANDIDATE_BALLOT == state) &&
            leadershipTermId == this.leadershipTermId)
        {
            leaderMember = clusterMembers[leaderMemberId];
            this.logSessionId = logSessionId;

            if (this.logPosition < logPosition && null == logCatchup)
            {
                logCatchup = new LogCatchup(
                    localArchive,
                    memberStatusPublisher,
                    clusterMembers,
                    leaderMemberId,
                    thisMember.id(),
                    recoveryPlan,
                    ctx);

                state(State.FOLLOWER_CATCHUP, ctx.epochClock().time());
            }
            else
            {
                state(State.FOLLOWER_TRANSITION, ctx.epochClock().time());
            }

        }
        else if (leadershipTermId > this.leadershipTermId)
        {
            // TODO: query leader recovery log and catch up
        }
    }

    public void onRecoveryPlanQuery(final long correlationId, final int leaderMemberId, final int requestMemberId)
    {
    }

    public void onRecoveryPlan(final RecoveryPlanDecoder decoder)
    {
        if (null != logCatchup)
        {
            logCatchup.onLeaderRecoveryPlan(decoder);
        }
    }

    public void onRecordingLogQuery(
        final long correlationId,
        final int leaderMemberId,
        final int requestMemberId,
        final long fromLeadershipTermId,
        final int count,
        final boolean includeSnapshots)
    {
    }

    public void onRecordingLog(final RecordingLogDecoder decoder)
    {
        if (null != logCatchup)
        {
            logCatchup.onLeaderRecordingLog(decoder);
        }
    }

    public void onAppendedPosition(final long logPosition, final long leadershipTermId, final int followerMemberId)
    {
        clusterMembers[followerMemberId]
            .logPosition(logPosition)
            .leadershipTermId(leadershipTermId);
    }

    public void onCommitPosition(final long logPosition, final long leadershipTermId, final int leaderMemberId)
    {
        if (leadershipTermId > this.leadershipTermId)
        {
            // TODO: query leader recovery log and catch up
        }
    }

    void closeCatchUp()
    {
        CloseHelper.close(logCatchup);
        logCatchup = null;
    }

    State state()
    {
        return state;
    }

    ClusterMember leader()
    {
        return leaderMember;
    }

    long leadershipTermId()
    {
        return leadershipTermId;
    }

    void logSessionId(final int logSessionId)
    {
        this.logSessionId = logSessionId;
    }

    long logPosition()
    {
        return logPosition;
    }

    private int init(final long nowMs)
    {
        stateCounter = ctx.aeron().addCounter(0, "Election State");

        if (clusterMembers.length == 1)
        {
            ++leadershipTermId;
            leaderMember = thisMember;
            ctx.recordingLog().appendTerm(leadershipTermId, logPosition, nowMs);
            state(State.LEADER_TRANSITION, nowMs);
        }
        else if (ctx.appointedLeaderId() == thisMember.id())
        {
            nominationDeadlineMs = nowMs;
            state(State.NOMINATE, nowMs);
        }
        else
        {
            state(State.CANVASS, nowMs);
        }

        return 1;
    }

    private int canvass(final long nowMs)
    {
        int workCount = 0;

        if (nowMs >= (timeOfLastUpdateMs + statusIntervalMs))
        {
            timeOfLastUpdateMs = nowMs;
            for (final ClusterMember member : clusterMembers)
            {
                if (member != thisMember)
                {
                    memberStatusPublisher.canvassPosition(
                        member.publication(), logPosition, leadershipTermId, thisMember.id());
                }
            }

            workCount += 1;
        }

        if (ctx.appointedLeaderId() != Aeron.NULL_VALUE)
        {
            return  workCount;
        }

        final long canvassDeadlineMs = (isStartup ?
            TimeUnit.NANOSECONDS.toMillis(ctx.startupStatusTimeoutNs()) :
            TimeUnit.NANOSECONDS.toMillis(ctx.electionTimeoutNs())) +
            timeOfLastStateChangeMs;

        if (ClusterMember.isUnanimousCandidate(clusterMembers, thisMember) ||
            (ClusterMember.isQuorumCandidate(clusterMembers, thisMember) && nowMs >= canvassDeadlineMs))
        {
            nominationDeadlineMs = nowMs + random.nextInt((int)statusIntervalMs);
            state(State.NOMINATE, nowMs);
            workCount += 1;
        }

        return workCount;
    }

    private int nominate(final long nowMs)
    {
        if (nowMs >= nominationDeadlineMs)
        {
            thisMember.leadershipTermId(++leadershipTermId);
            ClusterMember.becomeCandidate(clusterMembers, thisMember.id());
            ctx.recordingLog().appendTerm(leadershipTermId, logPosition, nowMs);
            sequencerAgent.role(Cluster.Role.CANDIDATE);

            state(State.CANDIDATE_BALLOT, nowMs);
            return 1;
        }

        return 0;
    }

    private int candidateBallot(final long nowMs)
    {
        int workCount = 0;

        if (ClusterMember.hasWonVoteOnFullCount(clusterMembers, leadershipTermId))
        {
            state(State.LEADER_TRANSITION, nowMs);
            leaderMember = thisMember;
            workCount += 1;
        }
        else if (nowMs >= (timeOfLastStateChangeMs + TimeUnit.NANOSECONDS.toMillis(ctx.electionTimeoutNs())))
        {
            if (ClusterMember.hasMajorityVote(clusterMembers, leadershipTermId))
            {
                state(State.LEADER_TRANSITION, nowMs);
                leaderMember = thisMember;
            }
            else
            {
                state(State.CANVASS, nowMs);
            }

            workCount += 1;
        }
        else
        {
            for (final ClusterMember member : clusterMembers)
            {
                if (!member.isBallotSent())
                {
                    workCount += 1;
                    member.isBallotSent(memberStatusPublisher.requestVote(
                        member.publication(), logPosition, leadershipTermId, thisMember.id()));
                }
            }
        }

        return workCount;
    }

    private int followerBallot(final long nowMs)
    {
        int workCount = 0;

        if (nowMs >= (timeOfLastStateChangeMs + TimeUnit.NANOSECONDS.toMillis(ctx.electionTimeoutNs())))
        {
            state(State.CANVASS, nowMs);
            workCount += 1;
        }

        return workCount;
    }

    private int leaderTransition(final long nowMs)
    {
        sequencerAgent.becomeLeader();
        ClusterMember.resetLogPositions(clusterMembers, NULL_POSITION);
        clusterMembers[thisMember.id()].logPosition(logPosition);
        state(State.LEADER_READY, nowMs);

        return 1;
    }

    private int leaderReady(final long nowMs)
    {
        int workCount = 0;

        if (ClusterMember.haveVotersReachedPosition(clusterMembers, logPosition, leadershipTermId))
        {
            sequencerAgent.electionComplete();
            close();

            workCount += 1;
        }
        else if (nowMs > (timeOfLastUpdateMs + leaderHeartbeatIntervalMs))
        {
            timeOfLastUpdateMs = nowMs;

            for (final ClusterMember member : clusterMembers)
            {
                if (member != thisMember)
                {
                    memberStatusPublisher.newLeadershipTerm(
                        member.publication(), logPosition, leadershipTermId, thisMember.id(), logSessionId);
                }
            }

            workCount += 1;
        }

        return workCount;
    }

    private int followerCatchup(final long nowMs)
    {
        int workCount = 0;

        if (!logCatchup.isDone())
        {
            workCount += memberStatusAdapter.poll();
            workCount += logCatchup.doWork();
        }
        else
        {
            logPosition = logCatchup.targetPosition();
            sequencerAgent.catchupLog(logCatchup);

            state(State.FOLLOWER_TRANSITION, nowMs);
            workCount += 1;
        }

        return  workCount;
    }

    private int followerTransition(final long nowMs)
    {
        sequencerAgent.updateMemberDetails();

        final ChannelUri channelUri = followerLogChannel(ctx.logChannel(), thisMember.logEndpoint(), logSessionId);

        sequencerAgent.recordLogAsFollower(channelUri.toString(), logSessionId);
        sequencerAgent.awaitServicesReady(channelUri, logSessionId);
        state(State.FOLLOWER_READY, nowMs);

        return 1;
    }

    private int followerReady(final long nowMs)
    {
        int workCount = 1;
        final Publication publication = leaderMember.publication();

        if (memberStatusPublisher.appendedPosition(publication, logPosition, leadershipTermId, thisMember.id()))
        {
            sequencerAgent.electionComplete();
            close();

            workCount += 0;
        }
        else if (nowMs >= (timeOfLastStateChangeMs + TimeUnit.NANOSECONDS.toMillis(ctx.electionTimeoutNs())))
        {
            state(State.CANVASS, nowMs);
            workCount += 1;
        }

        return workCount;
    }

    private void state(final State state, final long nowMs)
    {
        //System.out.println(this.state + " -> " + state);
        timeOfLastStateChangeMs = nowMs;
        this.state.exit(this);
        this.state = state;
        stateCounter.setOrdered(state.code());

        if (State.CANVASS == state)
        {
            ClusterMember.reset(clusterMembers);
            thisMember.leadershipTermId(leadershipTermId).logPosition(logPosition);
            sequencerAgent.role(Cluster.Role.FOLLOWER);
        }
    }

    private void placeVote(final long candidateTermId, final int candidateId, final boolean vote)
    {
        memberStatusPublisher.placeVote(
            clusterMembers[candidateId].publication(),
            candidateTermId,
            candidateId,
            thisMember.id(),
            vote);
    }

    private static ChannelUri followerLogChannel(final String logChannel, final String logEndpoint, final int sessionId)
    {
        final ChannelUri channelUri = ChannelUri.parse(logChannel);
        channelUri.put(CommonContext.ENDPOINT_PARAM_NAME, logEndpoint);
        channelUri.put(CommonContext.SESSION_ID_PARAM_NAME, Integer.toString(sessionId));

        return channelUri;
    }
}
