# binderfuzz

Drives binderd64 over its real wire protocol (bproto.h): processes with several threads, nested calls, replies, objects
(nodes, handles, fds, memfd buffers), refcounts, death notifications, process and thread kills at any moment.

    NDK-free host build, from native/guest64/fuzz:
    gcc -O1 -g -o binderfuzz binderfuzz.c
    gcc -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer \
        -I. -o binderd_san ../36/binderd.c

    ./binderfuzz ./binderd_san regress                 # the five reported bugs, one scripted scenario each
    ./binderfuzz ./binderd_san fuzz 1 32 80000         # realistic clients
    ./binderfuzz ./binderd_san fuzz 1 32 80000 -H      # + hostile clients

Needs Linux (memfd, /proc, epoll). Sanitizer output of a failing seed is left in /tmp/bfuzz-seed<N>.err.
