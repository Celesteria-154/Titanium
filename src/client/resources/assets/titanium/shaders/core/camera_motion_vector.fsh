#version 330

uniform sampler2D DepthSampler;
#ifdef TITANIUM_IMAGE_MOTION
uniform sampler2D CurrentColorSampler;
uniform sampler2D PreviousColorSampler;
uniform sampler2D PreviousDepthSampler;
#moj_import <titanium:image_motion_refinement.glsl>
#endif

layout(std140) uniform MotionData {
    mat4 CurrentInverseProjection;
    mat4 CurrentInverseModelView;
    mat4 CurrentModelView;
    mat4 CurrentUnjitteredProjection;
    mat4 PreviousModelView;
    mat4 PreviousUnjitteredProjection;
    vec4 CameraDeltaAndClear;
    ivec4 FrameSizeAndFlags;
#ifdef TITANIUM_IMAGE_MOTION
    vec4 PreviousMinusCurrentJitter;
#endif
};

out vec2 fragMotion;

bool invalid(vec4 value) {
    return any(isnan(value)) || any(isinf(value));
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(DepthSampler, pixel, 0).r;
    vec2 size = vec2(FrameSizeAndFlags.xy);
    vec2 ndc = 2.0 * gl_FragCoord.xy / size - 1.0;
    float ndcDepth = FrameSizeAndFlags.z != 0 ? 2.0 * depth - 1.0 : depth;
    vec4 view = CurrentInverseProjection * vec4(ndc, ndcDepth, 1.0);
    if (depth == CameraDeltaAndClear.w) {
        // Clear depth represents a background direction at infinity. Rotation matters,
        // but camera translation and view-bob translation must not move that direction.
        // Reverse-Z infinite-far projection can legitimately produce view.w == 0 here.
        vec4 direction = CurrentInverseModelView * vec4(view.xyz, 0.0);
        vec4 currentSky = CurrentUnjitteredProjection * (CurrentModelView * direction);
        vec4 previousSky = PreviousUnjitteredProjection * (PreviousModelView * direction);
        if (invalid(view) || invalid(direction) || invalid(currentSky) || invalid(previousSky)
                || currentSky.w <= 1.0e-12 || previousSky.w <= 1.0e-12) {
            fragMotion = vec2(0.0);
            return;
        }
        vec2 skyDelta = (previousSky.xy / previousSky.w - currentSky.xy / currentSky.w) * size * 0.5;
        fragMotion = vec2(skyDelta.x, -skyDelta.y);
        return;
    }
    if (invalid(view) || abs(view.w) <= 1.0e-12) {
        fragMotion = vec2(0.0);
        return;
    }
    view /= view.w;

    vec4 currentRelative = CurrentInverseModelView * vec4(view.xyz, 1.0);
    if (invalid(currentRelative) || abs(currentRelative.w) <= 1.0e-12) {
        fragMotion = vec2(0.0);
        return;
    }
    currentRelative /= currentRelative.w;

    vec4 currentClip = CurrentUnjitteredProjection * (CurrentModelView * vec4(currentRelative.xyz, 1.0));
    if (invalid(currentClip) || currentClip.w <= 1.0e-12) {
        fragMotion = vec2(0.0);
        return;
    }
    vec2 currentNdc = currentClip.xy / currentClip.w;
    vec2 currentLowerLeft = (currentNdc + 1.0) * size * 0.5 - 0.5;

    vec3 previousRelative = currentRelative.xyz + CameraDeltaAndClear.xyz;
    vec4 previousClip = PreviousUnjitteredProjection * (PreviousModelView * vec4(previousRelative, 1.0));
    if (invalid(previousClip) || previousClip.w <= 1.0e-12) {
        fragMotion = vec2(0.0);
        return;
    }
    vec2 previousNdc = previousClip.xy / previousClip.w;
    vec2 previousLowerLeft = (previousNdc + 1.0) * size * 0.5 - 0.5;
    // NGX backward motion: current pixel + MV = previous pixel, in top-left pixels.
    // The interop input blit flips texel rows; it does not change vector components.
    vec2 lowerLeftDelta = previousLowerLeft - currentLowerLeft;
#ifdef TITANIUM_IMAGE_MOTION
    if (FrameSizeAndFlags.w != 0) {
        float previousDepth = previousClip.z / previousClip.w;
        if (FrameSizeAndFlags.z != 0) previousDepth = previousDepth * 0.5 + 0.5;
        lowerLeftDelta = titaniumRefineImageMotion(CurrentColorSampler, PreviousColorSampler,
                PreviousDepthSampler, gl_FragCoord.xy, size, lowerLeftDelta,
                PreviousMinusCurrentJitter.xy, previousDepth, CameraDeltaAndClear.w);
    }
#endif
    fragMotion = vec2(lowerLeftDelta.x, -lowerLeftDelta.y);
}
