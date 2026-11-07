/* AEmulator Plus 2026-10-27: stand-in for Intel CE4100 libgdl/libgma (Logitech Revue). There is no /dev/gdl here, so every call
 * reports GDL_SUCCESS (0). Freestanding i386, no libc. */
typedef unsigned int u32;
int gma_pixmap_get_info(void){ return 0; }
int gma_pixmap_alloc(void){ return 0; }
int gma_pixmap_release(void){ return 0; }
int gma_pixmap_add_ref(void){ return 0; }
