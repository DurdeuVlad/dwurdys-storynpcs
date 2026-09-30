package com.storynpcs.script;

/**
 * The target hook matrix (P9-2): every CustomNPCs-style hook mapped to a typed
 * StoryNPCs context. Each hook declares its invocation budget class.
 */
public enum ScriptHook {
    INIT(BudgetClass.STANDARD),
    TICK(BudgetClass.TICK),
    INTERACT(BudgetClass.STANDARD),
    DAMAGED(BudgetClass.STANDARD),
    KILLED(BudgetClass.STANDARD),
    TARGET(BudgetClass.STANDARD),
    DIALOG(BudgetClass.STANDARD),
    QUEST(BudgetClass.STANDARD),
    TIMER(BudgetClass.STANDARD);

    public enum BudgetClass { TICK, STANDARD }

    private final BudgetClass budgetClass;

    ScriptHook(BudgetClass budgetClass) {
        this.budgetClass = budgetClass;
    }

    public BudgetClass budgetClass() {
        return budgetClass;
    }
}
