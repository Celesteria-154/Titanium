package celeste.titanium.client.render;

/** 同期的なワールド境界で借用するリソース識別子。生成・解放は元の所有者が行う。 */
final class FrameResourceIdentity {
    private Object target, color, depth;
    private int width, height;

    /**
     * 同期描画境界で借用するターゲット・色・深度の参照と実寸法を記録する。
     *
     * @param target 操作対象の描画先またはリソース
     * @param color 借用するカラーリソースのオブジェクト識別
     * @param depth 借用する深度リソースのオブジェクト識別
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     */
    void capture(Object target, Object color, Object depth, int width, int height) {
        this.target = target;
        this.color = color;
        this.depth = depth;
        this.width = width;
        this.height = height;
    }

    boolean matches(Object target, Object color, Object depth, int width, int height) {
        // 寸法だけで一致とせず、描画先・色・深度のオブジェクト同一性も確認する。
        return this.target != null && this.color != null && this.depth != null && width > 0 && height > 0
                && this.target == target && this.color == color && this.depth == depth
                && this.width == width && this.height == height;
    }

    void clear() {
        target = color = depth = null;
        width = height = 0;
    }
}
