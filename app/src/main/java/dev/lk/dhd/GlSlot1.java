package dev.lk.dhd;

/** The GL server of VM slot 1 (process :gl1): the slot-0 class runs in :gl, one process per machine. */
public final class GlSlot1 extends Slot {
    @Override protected String which() { return "glserverd"; }
}
