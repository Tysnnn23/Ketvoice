package com.ketvoice.app.glass

/**
 * Optical primitives adapted from PrismalAGSL (MIT), revision
 * c853feb2e8b375fc8eb2dcce25a6d4f18250fb68. See assets/licenses/PrismalAGSL.txt.
 * Keep our capture-coordinate and zero-normal guards: this is not a dependency
 * on Prismal's Compose/SDK versions or its demo application.
 */
internal const val PRISMAL_OPTICS_AGSL = """
float prismalArcLift(float x) {
    x = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}

float prismalSpecular(float2 normal, float2 light, float falloff) {
    return pow(abs(dot(normal, light)), falloff);
}
"""
