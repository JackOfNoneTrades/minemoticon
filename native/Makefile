#!/usr/bin/make -f
# Builds the freetype-jni native library for the current platform.
# Requires: cmake, a C++ compiler, JDK with javac
#
# Usage:
#   make              - build for current platform
#   make clean        - clean build artifacts
#   make install      - copy the built lib to the mod resources

FREETYPE_VERSION := 2.14.3
FREETYPE_DIR := freetype-$(FREETYPE_VERSION)
FREETYPE_TAR := freetype-$(FREETYPE_VERSION).tar.gz
FREETYPE_URL := https://download-mirror.savannah.gnu.org/releases/freetype/$(FREETYPE_TAR)

BUILD_DIR := build
JNI_DIR := freetype-jni/jni
JAVA_DIR := freetype-jni/freetype-jni

UNAME_S := $(shell uname -s)
UNAME_M := $(shell uname -m)

ifeq ($(UNAME_S),Darwin)
  ifeq ($(UNAME_M),arm64)
    PLATFORM := macos-arm64
    LIB_EXT := dylib
  else
    PLATFORM := macos-x64
    LIB_EXT := dylib
  endif
else ifeq ($(UNAME_S),Linux)
  PLATFORM := linux-x64
  LIB_EXT := so
else
  PLATFORM := windows-x64
  LIB_EXT := dll
endif

LIB_NAME := libfreetype-jni.$(LIB_EXT)
OUTPUT_DIR := ../src/main/resources/natives/$(PLATFORM)

.PHONY: all clean install

all: $(BUILD_DIR)/$(LIB_NAME)

# Download FreeType source
$(FREETYPE_TAR):
	curl -sL -o $@ $(FREETYPE_URL)

$(FREETYPE_DIR)/CMakeLists.txt: $(FREETYPE_TAR)
	tar xzf $(FREETYPE_TAR)
	touch $@

# Symlink freetype source for CMake
freetype-jni/freetype2: $(FREETYPE_DIR)/CMakeLists.txt
	ln -sf ../$(FREETYPE_DIR) freetype-jni/freetype2

# Build with CMake
$(BUILD_DIR)/$(LIB_NAME): freetype-jni/freetype2
	mkdir -p $(BUILD_DIR)
	cd $(BUILD_DIR) && JAVA_HOME=$(shell /usr/libexec/java_home 2>/dev/null || echo "$$JAVA_HOME") \
		cmake ../freetype-jni \
		-DCMAKE_BUILD_TYPE=Release \
		-DFT_DISABLE_HARFBUZZ=ON \
		-DFT_DISABLE_BROTLI=ON \
		-DFT_DISABLE_BZIP2=ON \
		-DFT_DISABLE_PNG=ON \
		-DFT_DISABLE_ZLIB=ON
	cd $(BUILD_DIR) && cmake --build . --config Release
	@echo "Built: $(BUILD_DIR)/$(LIB_NAME) for $(PLATFORM)"

install: $(BUILD_DIR)/$(LIB_NAME)
	mkdir -p $(OUTPUT_DIR)
	cp $(BUILD_DIR)/$(LIB_NAME) $(OUTPUT_DIR)/
	@echo "Installed to $(OUTPUT_DIR)/$(LIB_NAME)"

clean:
	rm -rf $(BUILD_DIR)
	rm -rf freetype-jni/freetype2
	rm -rf $(FREETYPE_DIR)
	rm -f $(FREETYPE_TAR)
