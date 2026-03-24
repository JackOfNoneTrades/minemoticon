const std = @import("std");

const common_freetype_sources = [_][]const u8{
    "src/autofit/autofit.c",
    "src/base/ftbase.c",
    "src/base/ftbbox.c",
    "src/base/ftbdf.c",
    "src/base/ftbitmap.c",
    "src/base/ftcid.c",
    "src/base/ftdebug.c",
    "src/base/ftfstype.c",
    "src/base/ftgasp.c",
    "src/base/ftglyph.c",
    "src/base/ftgxval.c",
    "src/base/ftinit.c",
    "src/base/ftmm.c",
    "src/base/ftotval.c",
    "src/base/ftpatent.c",
    "src/base/ftpfr.c",
    "src/base/ftstroke.c",
    "src/base/ftsynth.c",
    "src/base/fttype1.c",
    "src/base/ftwinfnt.c",
    "src/bdf/bdf.c",
    "src/bzip2/ftbzip2.c",
    "src/cache/ftcache.c",
    "src/cff/cff.c",
    "src/cid/type1cid.c",
    "src/gzip/ftgzip.c",
    "src/lzw/ftlzw.c",
    "src/pcf/pcf.c",
    "src/pfr/pfr.c",
    "src/psaux/psaux.c",
    "src/pshinter/pshinter.c",
    "src/psnames/psnames.c",
    "src/raster/raster.c",
    "src/sdf/sdf.c",
    "src/sfnt/sfnt.c",
    "src/smooth/smooth.c",
    "src/svg/svg.c",
    "src/truetype/truetype.c",
    "src/type1/type1.c",
    "src/type42/type42.c",
    "src/winfonts/winfnt.c",
};

fn requiredOption(b: *std.Build, name: []const u8, description: []const u8) []const u8 {
    return b.option([]const u8, name, description) orelse @panic("missing required build option");
}

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const freetype_src = requiredOption(b, "freetype-src", "Path to the FreeType source root");
    const java_include = requiredOption(b, "java-include", "Path to JAVA_HOME/include");
    const jni_md_include = requiredOption(b, "jni-md-include", "Path to the target-specific jni_md.h directory");
    const generated_headers = requiredOption(b, "generated-headers", "Path to the javac -h output directory");

    var freetype_sources = std.ArrayList([]const u8).init(b.allocator);
    var freetype_flags = std.ArrayList([]const u8).init(b.allocator);
    defer freetype_sources.deinit();
    defer freetype_flags.deinit();

    freetype_sources.append(switch (target.result.os.tag) {
        .windows => "builds/windows/ftsystem.c",
        else => "builds/unix/ftsystem.c",
    }) catch @panic("out of memory");
    freetype_sources.appendSlice(&common_freetype_sources) catch @panic("out of memory");
    freetype_flags.appendSlice(&.{
        "-std=c99",
        "-DFT2_BUILD_LIBRARY",
    }) catch @panic("out of memory");

    if (target.result.os.tag != .windows) {
        freetype_flags.appendSlice(&.{
            "-DHAVE_UNISTD_H",
            "-DHAVE_FCNTL_H",
        }) catch @panic("out of memory");
    }

    const freetype = b.addStaticLibrary(.{
        .name = "freetype",
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    freetype.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ freetype_src, "include" }) });
    freetype.addCSourceFiles(.{
        .root = .{ .cwd_relative = freetype_src },
        .files = freetype_sources.items,
        .flags = freetype_flags.items,
    });

    const jni = b.addSharedLibrary(.{
        .name = "freetype-jni",
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    jni.addIncludePath(.{ .cwd_relative = generated_headers });
    jni.addIncludePath(.{ .cwd_relative = java_include });
    jni.addIncludePath(.{ .cwd_relative = jni_md_include });
    jni.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ freetype_src, "include" }) });
    jni.addCSourceFiles(.{
        .files = &.{"jni/freetype_jni.c"},
        .flags = &.{"-std=c99"},
    });
    jni.linkLibrary(freetype);

    b.installArtifact(jni);
}
