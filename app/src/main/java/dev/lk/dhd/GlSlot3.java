package dev.lk.dhd;

/** The GL server of VM slot 3 (process :gl3): the slot-0 class runs in :gl, one process per machine. */
public final class GlSlot3 extends Slot {
    @Override protected String which() { return "glserverd"; }
}
