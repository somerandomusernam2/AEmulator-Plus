package dev.lk.dhd;

/** The binder daemon of VM slot 2 (process :binder2): the slot-0 class runs in :binder, one process per machine. */
public final class BinderSlot2 extends Slot {
    @Override protected String which() { return "binderd"; }
}
