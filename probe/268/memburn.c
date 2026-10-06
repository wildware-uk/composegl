// memburn <MB>: walks a buffer of that size forever, reading and writing every cache line,
// so the caches and the memory bus stay busy (a stand-in for builds and an emulator on the box).
#include <stdlib.h>
#include <string.h>
int main(int argc, char **argv) {
    size_t n = (size_t)atoi(argv[1]) << 20;
    volatile unsigned char *a = malloc(n);
    memset((void *)a, 1, n);
    for (;;) for (size_t i = 0; i < n; i += 64) a[i] += 1;
}
