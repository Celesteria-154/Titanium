#pragma once

namespace titanium {
// A caller must establish color semantics before opting in; float format alone is not sufficient.
enum class FrameColorEncoding { LdrUnorm, LinearHdrFloat };
}
