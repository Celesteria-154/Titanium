package celeste.titanium.client.render;

/** 単一の入力消費者に渡すCPU側の準備状態。GPUストレージと履歴更新は呼び出し元が所有する。 */
final class FramePacketPreparation<T> {
    private final FrameResourceIdentity resources=new FrameResourceIdentity();
    private T value;
    private float fov;
    boolean available(){return value!=null;}
    /**
     * ターゲットとFOVに紐づく早期確定入力を一つだけ保持し、重複登録を検出する。
     *
     * @param next 同じターゲットとFOVに対して一度だけ消費する早期確定結果
     * @param target 操作対象の描画先またはリソース
     * @param colour 借用するカラーリソース
     * @param depth 借用する深度リソースのオブジェクト識別
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param fov この入力で実際に使用するFOV。度単位
     */
    void offer(T next,Object target,Object colour,Object depth,int width,int height,float fov){
        if(value!=null || next==null || target==null || colour==null || depth==null || width<=0 || height<=0
                || !Float.isFinite(fov) || fov<=0 || fov>=180)throw new IllegalStateException("Invalid/duplicate early packet preparation");
        resources.capture(target,colour,depth,width,height);this.fov=fov;value=next;
    }
    /**
     * ターゲット・寸法・FOVの同一性を確認し、保留入力を消費して返す。
     *
     * @param target 操作対象の描画先またはリソース
     * @param colour 借用するカラーリソース
     * @param depth 借用する深度リソースのオブジェクト識別
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param fov この入力で実際に使用するFOV。度単位
     * @return 同じターゲットとFOVについて、一度だけ消費する早期確定結果
     */
    T take(Object target,Object colour,Object depth,int width,int height,float fov){
        T result=value;
        // 準備時と消費時のターゲット、寸法、FOVが同一の場合だけ、取得済み入力を一度だけ返す。
        boolean matches=result!=null && resources.matches(target,colour,depth,width,height)
                && Float.floatToIntBits(this.fov)==Float.floatToIntBits(fov);
        clear(); // 準備状態を破棄し、同じ入力を古い描画先へ再適用することを防ぐ。
        if(!matches)throw new IllegalStateException("Early packet target/FOV changed or already consumed");
        return result;
    }
    void clear(){value=null;fov=0;resources.clear();}
}
