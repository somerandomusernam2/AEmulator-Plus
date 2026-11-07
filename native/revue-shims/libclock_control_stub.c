/* AEmulator Plus 2026-10-27: stand-in for Intel CE4100 libclock_control (Logitech Revue): no ISMD hardware here, every call reports success (0). Freestanding i386. */
int _clock_control_write(void){ return 0; }
int _clock_control_read(void){ return 0; }
int cc_access_resource(void){ return 0; }
