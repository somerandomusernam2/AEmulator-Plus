package dev.lk.dhd;

/** The binder daemon of VM slot 1 (process :binder1): the slot-0 class runs in :binder, one process per machine. */
public final class BinderSlot1 extends Slot {
    @Override protected String which() { return "binderd"; }
}
