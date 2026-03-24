#!/usr/bin/make -f

ZIG_VERSION ?= 0.13.0
FREETYPE_VERSION ?= 2.14.3

.PHONY: all clean install sync-freetype verify-freetype zig-host zig-all setup-zig

all: zig-host

setup-zig:
	ZIG_VERSION=$(ZIG_VERSION) ./build-zig.sh --setup-only

sync-freetype:
	FREETYPE_VERSION=$(FREETYPE_VERSION) ./sync-freetype.sh --sync

verify-freetype:
	FREETYPE_VERSION=$(FREETYPE_VERSION) ./sync-freetype.sh --verify

zig-host: verify-freetype
	ZIG_VERSION=$(ZIG_VERSION) FREETYPE_VERSION=$(FREETYPE_VERSION) ./build-zig.sh host

zig-all: verify-freetype
	ZIG_VERSION=$(ZIG_VERSION) FREETYPE_VERSION=$(FREETYPE_VERSION) ./build-zig.sh all

install: zig-host

clean:
	rm -rf build/zig
