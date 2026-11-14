#version 330

uniform sampler2D DepthSampler;

out float fragDepth;

void main() {
    fragDepth = texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r;
}
