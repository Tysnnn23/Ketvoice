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

// Schlick's Fresnel term with the reflectance of glass (F0 = 0.04), less its constant part:
// what a surface seen ever more edge-on adds to the light it reflects. x is 0 on the flat
// centre of the lens band and 1 at the rim.
float prismalFresnel(float x) {
    float t = clamp(x, 0.0, 1.0);
    float t2 = t * t;
    return 0.96 * t2 * t2 * t;
}

float prismalSpecular(float2 normal, float2 light, float falloff) {
    return pow(max(dot(normal, light), 0.0), falloff);
}
"""
