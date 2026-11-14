package celeste.titanium.client.render.enhanced;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** パック内のインターフェース候補を探索する。実際の有効化にはシェーダーと描画構造の検証が必要。 */
final class EnhancedPackCompatibility {
    private EnhancedPackCompatibility() {}
    /**
     * ZIP内のTAA・ジッター・受け渡しソースを照合し、単一の対応インターフェース候補を返す。
     *
     * @param archive インターフェース解析対象のパックZIP
     * @return 一意に照合できたAAインターフェース規則。候補がない場合は空
     */
    static Optional<EnhancedShaderAdapter.Rule> inspect(Path archive) throws IOException {
        try(var zip=new ZipFile(archive.toFile())) {
            var names=new HashSet<String>();String root=null;
            var entries=zip.entries();int count=0;
            // ZIP内の重複項目と複数のshaderルートを除外し、読み取るインターフェースを一意にする。
            while(entries.hasMoreElements()) {
                var e=entries.nextElement();String n=e.getName();
                if(++count>8192||!names.add(n))throw new IOException("Ambiguous or excessive pack entries");
                if(n.endsWith("shaders/lib/antialiasing/taa.glsl")) {
                    if(root!=null)throw new IOException("Multiple shader roots");
                    root=n.substring(0,n.length()-"lib/antialiasing/taa.glsl".length());
                }
            }
            if(root==null)return Optional.empty();
            String taa=read(zip,root+"lib/antialiasing/taa.glsl");
            var candidates=new ArrayList<EnhancedShaderAdapter.Rule>();
            // TAA・ジッター・カラー受け渡しの組を確認し、既知の単一インターフェースだけを候補にする。
            for(var rule:EnhancedShaderAdapter.Rule.values())if(EnhancedShaderAdapter.hasAaInterface(taa,rule)) {
                String jitter=root+(rule==EnhancedShaderAdapter.Rule.BSL?"lib/util/jitter.glsl":"lib/antialiasing/jitter.glsl");
                if(!EnhancedShaderAdapter.hasJitterInterface(read(zip,jitter)))continue;
                String consumer=root+(rule==EnhancedShaderAdapter.Rule.SOLAS?"programs/composite15.glsl":rule==EnhancedShaderAdapter.Rule.BSL?"program/composite7.glsl":"program/composite6.glsl");
                String producer=root+(rule==EnhancedShaderAdapter.Rule.SOLAS?"programs/composite14.glsl":rule==EnhancedShaderAdapter.Rule.BSL?"program/composite6.glsl":"program/composite5.glsl");
                if(zip.getEntry(producer)!=null && EnhancedShaderAdapter.hasColourInterface(read(zip,consumer),rule))candidates.add(rule);
            }
            return candidates.size()==1?Optional.of(candidates.getFirst()):Optional.empty();
        }
    }
    /**
     * 指定ZIP項目をサイズ制限付きで読み、インターフェース解析用のUTF-8文字列へ変換する。
     *
     * @param zip 読み取り専用で開いたシェーダーパックZIP
     * @param name 対象または診断区間を識別する名前
     * @return サイズと存在を検証したZIP項目のUTF-8ソース
     */
    private static String read(ZipFile zip,String name) throws IOException {
        ZipEntry e=zip.getEntry(name);
        if(e==null||e.isDirectory()||e.getSize()<0||e.getSize()>2_000_000)throw new IOException("Missing or excessive interface source: "+name);
        try(var in=zip.getInputStream(e)) {
            byte[] bytes=in.readNBytes(2_000_001);
            if(bytes.length>2_000_000)throw new IOException("Inflated shader source bound");
            return new String(bytes,StandardCharsets.UTF_8);
        }
    }
}
