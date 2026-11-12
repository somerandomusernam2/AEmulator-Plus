package dev.lk.dhd;

/** The GL server of VM slot 2 (process :gl2): the slot-0 class runs in :gl, one process per machine. */
public final class GlSlot2 extends Slot {
    @Override protected String which() { return "glserverd"; }
}
