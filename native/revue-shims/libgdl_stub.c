/* AEmulator Plus 2026-10-27: stand-in for Intel CE4100 libgdl/libgma (Logitech Revue). There is no /dev/gdl here, so every call
 * reports GDL_SUCCESS (0). Freestanding i386, no libc. */
typedef unsigned int u32;
int gdl_plane_set_rect(void){ return 0; }
int gdl_check_tvmode(void){ return 0; }
int gdl_plane_capabilities(void){ return 0; }
int gdl_map_surface(void){ return 0; }
int gdl_unmap_surface(void){ return 0; }
/* gdl_port_recv(port, cmd, buf, size), as av_settings calls it (port is always 2 = HDMI):
 *  cmd 2, size 0x81: one 128-byte EDID block, buf[0] = block number. Report an all-zero block (extension count 0), success:
 *      leaving it untouched gave a random extension count and a random CEA block, and a failure crashes av_settings;
 *  cmd 0, size 0x18: HDMI audio capability entry number buf[1]; it ends the list on the first failure, so report one at once.
 *      Success with the buffer untouched made it print "HDMIAudioFmt[n] ... Channels 1082132168" over a million times;
 *  anything else (cmd 5, ...): success with a zeroed buffer. */
int gdl_port_recv(int port, int cmd, unsigned char* buf, u32 size){
    u32 i;
    if(cmd==0) return 1;
    if(buf) for(i=(cmd==2?1:0); i<size; i++) buf[i]=0;
    return 0;
}
int gdl_dbg_dump_csc_info(void){ return 0; }
int gdl_plane_set_uint(void){ return 0; }
int gdl_clear_surface(void){ return 0; }
int gdl_closed_caption_source(void){ return 0; }
int gdl_plane_get_uint(void){ return 0; }
int gdl_event_register(void){ return 0; }
const char* gdl_dbg_string_attribute(int v){ return ""; }
int gdl_plane_get_rect(void){ return 0; }
int gdl_set_display_info(void){ return 0; }
int gdl_plane_set_attr(void){ return 0; }
const char* gdl_dbg_string_audio_format(int v){ return ""; }
int gdl_get_palette(void){ return 0; }
int gdl_plane_get_attr(void){ return 0; }
const char* gdl_dbg_string_pixel_format(int v){ return ""; }
const char* gdl_dbg_string_speaker_map(int v){ return ""; }
int gdl_free_surface(void){ return 0; }
int gdl_debug_log_start(void){ return 0; }
int gdl_set_palette(void){ return 0; }
const char* gdl_get_error_string(int e){ return "ok"; }
int gdl_plane_config_begin(void){ return 0; }
int gdl_plane_reset(void){ return 0; }
int gdl_wait_for_vblank(void){ return 0; }
int gdl_plane_config_end(void){ return 0; }
int gdl_get_display_info(int id,u32*info){ if(info){info[0]=id;info[1]=1280;info[2]=720;info[3]=0;info[4]=0;} return 0; }
int gdl_close(void){ return 0; }
int gdl_flush_surface(void){ return 0; }
int gdl_flip(void){ return 0; }
int gdl_create_surface(void){ return 0; }
int gdl_get_surface_info(void){ return 0; }
int _gdl_init(void){ return 0; }
int gdl_get_upp_zorder(void){ return 0; }
int gdl_heap_in_use(void){ return 0; }
int gdl_dbg_dump_display_info(void){ return 0; }
int gdl_get(void){ return 0; }
int gdl_alloc_surface(void){ return 0; }
int gdl_dbg_dump_surface_info(void){ return 0; }
/* gdl_port_get_attr(port, attr, buf): av_settings asks port 2 (HDMI), attr 8 for the cable status; the "connected" flag is the
 * word at buf+0x24. Failing here logged "Failed to read HDMI cable status (gdl_ret 1)" for every poll (a million lines per boot)
 * and answered "connected" anyway, so report success and "connected". Any other attribute: not available, as before. */
int gdl_port_get_attr(int port, int attr, u32* buf){
    if(port==2 && attr==8 && buf){ buf[9]=1; return 0; }
    return 1;
}
int gdl_port_set_attr(void){ return 0; }
int gdl_plane_get_int(void){ return 0; }
int gdl_set_upp_zorder(void){ return 0; }
int gdl_debug_log_stop(void){ return 0; }
const char* gdl_dbg_string_plane_id(int v){ return ""; }
int gdl_attach_heap(void){ return 0; }
const char* gdl_dbg_string_sample_size(int v){ return ""; }
int gdl_plane_reset_all(void){ return 0; }
int gdl_get_driver_info(void){ return 0; }
int gdl_get_display_polarity(void){ return 0; }
int gdl_port_pd_unload(void){ return 0; }
int gdl_get_port_tvmode_by_index(void){ return 1; } /* end of the mode list at once: av_settings falls back to its built-in modes */
const char* gdl_dbg_string_refresh(int v){ return ""; }
int gdl_port_send(void){ return 0; }
const char* gdl_dbg_string_sampling_rate(int v){ return ""; }
int gdl_put(void){ return 0; }
int gdl_plane_set_int(void){ return 0; }
int gdl_event_unregister(void){ return 0; }
int gdl_port_pd_load(void){ return 0; }
