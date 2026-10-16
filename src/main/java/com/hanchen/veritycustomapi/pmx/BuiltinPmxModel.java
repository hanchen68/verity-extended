package com.hanchen.veritycustomapi.pmx;

import com.hanchen.veritycustomapi.mmd.VmdClip;

import java.nio.file.Path;

/** 可选的内置 PMX 模型功能的不可变句柄。 */
public record BuiltinPmxModel(Path directory, Path modelFile, MmdModelData data, VmdClip motion) {
}
