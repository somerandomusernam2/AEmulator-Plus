/* The shape of an RGB buffer, left by the engine's gralloc in the buffer's last bytes for the engine's hwcomposer:
 * the default gralloc's handle (fd, magic, flags, size, offset, base) has no room for it. */
struct rgb_note { uint32_t magic; int32_t w, h, stride, format; uint32_t pad[3]; };
#define RGB_NOTE 0x52474541u
struct rgb_note_handle { int version, num_fds, num_ints, fd, magic, flags, size, offset; uint64_t base; };
/* Only the engine's own gralloc leaves a note: its handle is 12, 1 fd, 8 ints, the magic right after the fd. Any other
 * gralloc's handle (the firmware's Mali/Amlogic gralloc4 has two fds, the same 0x3141592 magic at byte 24, and numbers
 * where base and size would be) read this way gave "size" = that magic and a wild "base": SurfaceFlinger died on it. */
static inline const struct rgb_note_handle *note_handle_of(const void *handle) {
    const struct rgb_note_handle *p = handle;
    if (!p || p->version != 12 || p->num_fds != 1 || p->num_ints < 6 || p->num_ints > 16 || p->magic != 0x3141592) return 0;
    return p;
}
static inline struct rgb_note *rgb_note_at(const void *handle) {
    const struct rgb_note_handle *p = note_handle_of(handle);
    if (!p || !p->base || p->size < 4096) return 0;
    return (struct rgb_note *)(p->base + (uint64_t)p->size - sizeof(struct rgb_note));
}
static inline const struct rgb_note *rgb_note_of(const void *handle) {
    const struct rgb_note *n = rgb_note_at(handle);
    return n && n->magic == RGB_NOTE && n->w > 0 && n->h > 0 && n->stride >= n->w ? n : 0;
}

/* What the engine's stable-C mapper (native/gralloc5, Android 17) answers about a buffer: kept in front of the 32
 * bytes of the notes above, in a gralloc built with AEMU_META. Shared by every process that maps the buffer, so a
 * dataspace set in one is read in another. */
/* (pad[0]: how many times the buffer was locked for writing) */
struct meta_note { uint32_t magic; int32_t w, h, format, stride, layers, dataspace, blend; uint64_t usage, id; char name[64]; uint64_t pad[2]; };
#define META_NOTE 0x4d454541u
#define META_ROOM (32 + sizeof(struct meta_note))
static inline struct meta_note *meta_note_at(const void *handle) {
    const struct rgb_note_handle *p = note_handle_of(handle);
    if (!p || !p->base || p->size < 4096) return 0;
    return (struct meta_note *)(p->base + (uint64_t)p->size - META_ROOM);
}
