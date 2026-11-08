#pragma once
typedef struct native_handle { int version; int numFds; int numInts; int data[0]; } native_handle_t;
typedef const native_handle_t *buffer_handle_t;
