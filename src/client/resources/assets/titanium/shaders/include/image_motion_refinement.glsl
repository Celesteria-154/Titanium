// Image-estimated residual motion, not vertex/surface velocity. Unknown matches retain camera MV.
// Coordinates here are GL lower-left texel centres; the caller owns NGX direction conversion.
vec3 titaniumMotionColor(sampler2D image, vec2 uv, vec2 texel) {
    // Symmetric tent filter for matching only. Never changes the colour sent to NGX or displayed.
    return (textureLod(image, uv + vec2(-0.5, -0.5) * texel, 0.0).rgb
            + textureLod(image, uv + vec2(0.5, -0.5) * texel, 0.0).rgb
            + textureLod(image, uv + vec2(-0.5, 0.5) * texel, 0.0).rgb
            + textureLod(image, uv + vec2(0.5, 0.5) * texel, 0.0).rgb) * 0.25;
}
float titaniumMatchCost(sampler2D previousColor, vec2 uv, vec2 texel, vec3 colors[5]) {
    const vec2 offsets[5] = vec2[5](vec2(0), vec2(1, 0), vec2(-1, 0), vec2(0, 1), vec2(0, -1));
    float cost = 0.0;
    for (int i = 0; i < 5; ++i) {
        vec3 difference = titaniumMotionColor(previousColor, uv + offsets[i] * texel, texel) - colors[i];
        cost += dot(difference, difference);
    }
    return cost / 15.0;
}

float titaniumPreviousDepth(sampler2D previousDepth, vec2 uv, float expected, float clearDepth) {
    vec2 coordinate = uv * vec2(textureSize(previousDepth, 0)) - 0.5;
    ivec2 base = ivec2(floor(coordinate));
    vec2 fraction = fract(coordinate);
    vec4 values = vec4(texelFetch(previousDepth, base, 0).r,
            texelFetch(previousDepth, base + ivec2(1, 0), 0).r,
            texelFetch(previousDepth, base + ivec2(0, 1), 0).r,
            texelFetch(previousDepth, base + ivec2(1, 1), 0).r);
    if (any(equal(values, vec4(clearDepth))) || any(isnan(values)) || any(isinf(values))) return -1.0;
    float sampled = mix(mix(values.x, values.y, fraction.x), mix(values.z, values.w, fraction.x), fraction.y);
    // Compare depth disparity relatively, rather than a large absolute tolerance near the far plane.
    float disparity = clearDepth == 1.0 ? 1.0 - min(sampled, expected) : max(sampled, expected);
    if (any(greaterThan(abs(values - vec4(expected)), vec4(max(1.0e-7, disparity * 0.02)))))
        return -1.0;
    return sampled;
}
bool titaniumMatchingDepth(sampler2D previousDepth, vec2 uv, float expected, float clearDepth) {
    return titaniumPreviousDepth(previousDepth, uv, expected, clearDepth) >= 0.0;
}

vec2 titaniumRefineImageMotion(sampler2D currentColor, sampler2D previousColor,
        sampler2D previousDepth, vec2 pixelCenter, vec2 size, vec2 cameraDelta,
        vec2 previousMinusCurrentJitter, float expectedPreviousDepth, float clearDepth) {
    vec2 texel = 1.0 / size;
    vec2 previousCenter = pixelCenter + cameraDelta + previousMinusCurrentJitter;
    if (any(lessThan(pixelCenter, vec2(4))) || any(greaterThan(pixelCenter, size - vec2(4)))
            || any(lessThan(previousCenter, vec2(4)))
            || any(greaterThan(previousCenter, size - vec2(4)))) return cameraDelta;
    vec2 previousUv = previousCenter * texel;
    float oldDepth = titaniumPreviousDepth(previousDepth, previousUv, expectedPreviousDepth, clearDepth);
    if (oldDepth < 0.0)
        return cameraDelta;
    // Colour-only changes can be jitter aliasing, lighting or postprocessing. Require independent
    // geometric evidence; strictly lateral/depth-invariant animation remains unsupported.
    float disparity = clearDepth == 1.0 ? 1.0 - min(oldDepth, expectedPreviousDepth)
            : max(oldDepth, expectedPreviousDepth);
    if (abs(oldDepth - expectedPreviousDepth) <= max(1.0e-6, disparity * 0.00005))
        return cameraDelta;
    const ivec2 offsets[5] = ivec2[5](ivec2(0), ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1));
    vec3 colors[5];
    float contrast = 0.0;
    for (int i = 0; i < 5; ++i) {
        colors[i] = titaniumMotionColor(currentColor, (pixelCenter + vec2(offsets[i])) * texel, texel);
        if (i != 0) {
            vec3 difference = colors[i] - colors[0];
            contrast += dot(difference, difference);
        }
    }
    if (contrast / 12.0 < 0.0008) return cameraDelta;
    float baseline = titaniumMatchCost(previousColor, previousUv, texel, colors);
    if (baseline < 0.0004) return cameraDelta;
    float bestCost = baseline;
    vec2 best = vec2(0);
    // Bounded coarse-to-fine search: at most two render pixels beyond camera reprojection.
    const float steps[3] = float[3](1.0, 0.5, 0.25);
    for (int level = 0; level < 3; ++level) {
        vec2 origin = best;
        for (int y = -1; y <= 1; ++y) for (int x = -1; x <= 1; ++x) {
            if (x == 0 && y == 0) continue;
            vec2 candidate = origin + vec2(x, y) * steps[level];
            vec2 uv = previousUv + candidate * texel;
            if (!titaniumMatchingDepth(previousDepth, uv, expectedPreviousDepth, clearDepth)) continue;
            float cost = titaniumMatchCost(previousColor, uv, texel, colors);
            if (cost < bestCost) { bestCost = cost; best = candidate; }
        }
    }
    // Large improvement required. No global reset, colour blend, contrast boost or sharpening.
    if (bestCost > baseline * 0.25 || bestCost > 0.0004 || dot(best, best) < 0.04)
        return cameraDelta;
    return cameraDelta + best;
}
