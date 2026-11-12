package dev.lk.dhd;

/** The binder daemon of VM slot 3 (process :binder3): the slot-0 class runs in :binder, one process per machine. */
public final class BinderSlot3 extends Slot {
    @Override protected String which() { return "binderd"; }
}
