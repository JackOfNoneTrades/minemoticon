#!/usr/bin/make -f

ZIG_VERSION ?= 0.13.0

.PHONY: all clean install zig-host zig-all setup-zig

all: zig-host

setup-zig:
	ZIG_VERSION=$(ZIG_VERSION) ./build-zig.sh --setup-only

zig-host:
	ZIG_VERSION=$(ZIG_VERSION) ./build-zig.sh host

zig-all:
	ZIG_VERSION=$(ZIG_VERSION) ./build-zig.sh all

install: zig-host

clean:
	rm -rf build/zig
