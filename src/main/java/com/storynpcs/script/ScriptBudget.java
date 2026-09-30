package com.storynpcs.script;

/**
 * Per-invocation execution quotas (P9-2). Limits are enforced cooperatively —
 * the host supplies a {@link Meter} counting instructions, allocations, and
 * canonical op calls; exceeding any bound throws {@link BudgetExceeded},
 * never crashes the server.
 */
public record ScriptBudget(
        long maxInstructions,
        long maxLiveMemoryBytes,
        int maxRecursionDepth,
        int maxCanonicalOps) {

    /** TICK hooks: tightest budget (per acceptance criteria). */
    public static ScriptBudget tick() {
        return new ScriptBudget(20_000, 1 << 20, 16, 32);
    }

    /** Other hooks: standard budget. */
    public static ScriptBudget standard() {
        return new ScriptBudget(100_000, 1 << 20, 16, 32);
    }

    public static ScriptBudget forHook(ScriptHook hook) {
        return hook.budgetClass() == ScriptHook.BudgetClass.TICK ? tick() : standard();
    }

    public static final class BudgetExceeded extends RuntimeException {
        private final String metric;
        public BudgetExceeded(String metric, long limit, long actual) {
            super("script budget exceeded on " + metric + ": limit " + limit + ", actual " + actual);
            this.metric = metric;
        }
        public String metric() { return metric; }
    }

    /** Counts resource consumption; over-budget throws, never returns false. */
    public static final class Meter {
        private final ScriptBudget budget;
        private long instructions;
        private long liveMemory;
        private int depth;
        private int canonicalOps;

        public Meter(ScriptBudget budget) {
            this.budget = budget;
        }

        public void instruction() {
            if (++instructions > budget.maxInstructions()) {
                throw new BudgetExceeded("instructions", budget.maxInstructions(), instructions);
            }
        }

        public void allocate(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("allocation bytes must be non-negative");
            }
            long limit = budget.maxLiveMemoryBytes();
            if (liveMemory > limit || bytes > limit - liveMemory) {
                throw new BudgetExceeded("liveMemory", limit, limit == Long.MAX_VALUE ? limit : limit + 1);
            }
            liveMemory += bytes;
        }

        public void release(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("released bytes must be non-negative");
            }
            liveMemory = Math.max(0, liveMemory - bytes);
        }

        public void enter() {
            if (++depth > budget.maxRecursionDepth()) {
                depth--;
                throw new BudgetExceeded("recursionDepth", budget.maxRecursionDepth(), depth + 1);
            }
        }

        public void exit() {
            depth = Math.max(0, depth - 1);
        }

        public void canonicalOp() {
            if (++canonicalOps > budget.maxCanonicalOps()) {
                throw new BudgetExceeded("canonicalOps", budget.maxCanonicalOps(), canonicalOps);
            }
        }
    }
}
