#version 330 core

uniform sampler2D OutputSampler;
uniform float Sharpness;
out vec4 fragColor;

vec4 samplePixel(ivec2 pixel, ivec2 size) {
    return texelFetch(OutputSampler, clamp(pixel, ivec2(0), size - 1), 0);
}

void main() {
    ivec2 size = textureSize(OutputSampler, 0);
    // NGX/D3D top-left rows -> destination GL bottom-left rows, exactly once.
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    pixel.y = size.y - 1 - pixel.y;
    vec4 center = samplePixel(pixel, size);
    vec3 north = samplePixel(pixel + ivec2(0, -1), size).rgb;
    vec3 south = samplePixel(pixel + ivec2(0, 1), size).rgb;
    vec3 east = samplePixel(pixel + ivec2(1, 0), size).rgb;
    vec3 west = samplePixel(pixel + ivec2(-1, 0), size).rgb;
    vec3 localMin = min(center.rgb, min(min(north, south), min(east, west)));
    vec3 localMax = max(center.rgb, max(max(north, south), max(east, west)));
    vec3 detail = center.rgb - (north + south + east + west) * 0.25;
    // Bound enhancement relative to local contrast to limit ringing/halos.
    vec3 limit = (localMax - localMin) * 0.25;
    vec3 enhancement = clamp(detail * (Sharpness * 0.5), -limit, limit);
    fragColor = vec4(clamp(center.rgb + enhancement, 0.0, 1.0), center.a);
}
