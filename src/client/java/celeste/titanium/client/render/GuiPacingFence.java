package celeste.titanium.client.render;

import com.mojang.blaze3d.buffers.GpuFence;
import java.util.Objects;

/** GPUデバイスごとに前回送信の完了確認を所有する。描画スレッドから使用する。 */
public final class GuiPacingFence implements AutoCloseable {
    private GpuFence previous;
    private Object owner;
    private boolean failed;
    private String failure = "";
    private long captures, waits, discards, failures;

    public boolean failed() { return failed; }
    public String failure() { return failure; }
    /**
     * 失敗を記録し、前回送信フェンスを破棄してペーシングを停止する。
     *
     * @param error 失敗した操作の結果または例外
     */
    public void fail(RuntimeException error) {
        failed = true;
        failure = error.toString();
        failures++;
        discard();
    }
    /**
     * 古いフェンスを返し、送信に成功した新しいフェンスと所有GPUデバイスを保持する。
     *
     * @param device フェンスの所有者を識別するGPUデバイス
     * @param fence 送信完了を確認するために所有するGPUフェンス
     */
    public void submitted(Object device, GpuFence fence) {
        Objects.requireNonNull(device); Objects.requireNonNull(fence);
        discard();
        if(failed) { fence.close(); return; }
        owner=device; previous=fence; captures++;
    }
    /**
     * 同一デバイスの前回フェンスを一度だけ待ち、1秒の待機上限超過時は失敗状態へ移す。
     *
     * フェンスは一度だけ消費し、待機上限を1秒とする。デバイスが交代した場合は旧フェンスを破棄する。
     *
     * @param device フェンスの所有者を識別するGPUデバイス
     * @return 同じデバイスの前回送信フェンスを待機できた場合はtrue
     */
    public boolean beforeGui(Object device) {
        if(failed)return false;
        // 別GPUデバイスのフェンスは再利用せず、旧参照を破棄して新しい送信を待つ。
        if(owner!=device){discard();owner=device;return false;}
        var fence=previous;
        // 待機前に所有フィールドを空にし、完了・例外のどちらでもフェンスを一度だけ解放する。
        previous=null;
        if(fence==null)return false;
        try(fence){
            if(!fence.awaitCompletion(1_000_000_000L)) {
                fail(new IllegalStateException("Previous GUI submit exceeded one-second pacing budget"));
                return false;
            }
            waits++;
            return true;
        }catch(RuntimeException error){fail(error);return false;}
    }
    /**
     * 保留フェンスを所有フィールドから外し、存在する場合は一度だけ解放する。
     */
    private void discard() {
        var fence=previous;previous=null;
        if(fence!=null){discards++;fence.close();}
    }
    /**
     * 所有するリソースと保留状態を解放する。
     */
    @Override public void close(){discard();owner=null;failed=false;failure="";}
    public Snapshot snapshot(){return new Snapshot(captures,waits,discards,failures,previous!=null,failed);}
    public record Snapshot(long captures,long waits,long discards,long failures,boolean pending,boolean disabledByFault) {}
}
