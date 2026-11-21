package com.commerceos.platform.audit;

import java.util.Map;
import java.util.UUID;

/** 감사 기록 한 건. before/after 에는 마스킹된 값만 넣는다. */
public record AuditEntry(
        UUID workspaceId,
        ActorType actorType,
        UUID actorMemberId,
        String actorLabel,
        String action,
        String outcome,
        String targetType,
        UUID targetId,
        UUID businessEntityId,
        UUID channelAccountId,
        String reason,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> metadata) {

    public static Builder builder(UUID workspaceId, String action, String targetType, UUID targetId) {
        return new Builder(workspaceId, action, targetType, targetId);
    }

    public static final class Builder {
        private final UUID workspaceId;
        private final String action;
        private final String targetType;
        private final UUID targetId;
        private ActorType actorType = ActorType.USER;
        private UUID actorMemberId;
        private String actorLabel;
        private String outcome = "SUCCESS";
        private UUID businessEntityId;
        private UUID channelAccountId;
        private String reason;
        private Map<String, Object> before;
        private Map<String, Object> after;
        private Map<String, Object> metadata;

        private Builder(UUID workspaceId, String action, String targetType, UUID targetId) {
            this.workspaceId = workspaceId;
            this.action = action;
            this.targetType = targetType;
            this.targetId = targetId;
        }

        public Builder actor(ActorType type, UUID memberId, String label) {
            this.actorType = type;
            this.actorMemberId = memberId;
            this.actorLabel = label;
            return this;
        }

        public Builder outcome(String outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder scope(UUID businessEntityId, UUID channelAccountId) {
            this.businessEntityId = businessEntityId;
            this.channelAccountId = channelAccountId;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder before(Map<String, Object> before) {
            this.before = before;
            return this;
        }

        public Builder after(Map<String, Object> after) {
            this.after = after;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public AuditEntry build() {
            return new AuditEntry(workspaceId, actorType, actorMemberId, actorLabel, action, outcome, targetType, targetId,
                    businessEntityId, channelAccountId, reason, before, after, metadata);
        }
    }
}
