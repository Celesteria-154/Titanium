package celeste.titanium.client.render.enhanced;

import java.util.*;

/** Iris処理後のGLSLを字句・文の単位で限定変換する。
 * 対応する構造だけを変換し、既存のTAA関数、履歴出力、後続エフェクトを保持する。 */
public final class EnhancedShaderAdapter {
    /** パックのリリース番号から独立したインターフェースの分類。 */
    public enum Rule { SOLAS, COMPLEMENTARY, BSL }
    public record Result(String source, boolean jitter, boolean primaryColour) {}
    private record Token(String text, int start, int end) {}
    private record Edit(int offset, String text) {}
    public static final String OWN = "titaniumEnhancedJitter";
    public static final String CLIP = "titaniumEnhancedClip";
    public static final String COLOUR = "titaniumEnhancedColour";
    public static final String FINAL_RAW = "titaniumEnhancedFinalRaw";
    public static final String FINAL_NOISE = "titaniumEnhancedFinalNoiseOnly";
    private EnhancedShaderAdapter() {}

    /**
     * メイン表示色が既知のTAA受け渡しだけで構成されるか調べ、CUTOUT合成に適する境界か判定する。
     *
     * @param source Irisの前処理後に取得した、位置情報を保持するGLSLソース
     * @return CUTOUT合成に必要な、既知のメインTAAだけの表示色受け渡しである場合はtrue
     */
    static boolean pureMainTaaBoundary(String source){
        var t=tokens(source);
        for(int i=1;i+3<t.size();i++)if(t.get(i-1).text.equals("void")&&t.get(i).text.equals("main")&&t.get(i+1).text.equals("(")){
            int c=matching(t,i+1,"(",")");if(!t.get(c+1).text.equals("{"))continue;int end=matching(t,c+1,"{","}");
            return t.subList(c+2,end).stream().filter(x->x.text.equals("color")).count()==3;
        }
        return false;
    }

    /**
     * 対応する断片ソースに元mainを呼ぶラッパーを追加し、採用済み断片の深度を位置7へ出力する。
     *
     * @param source Irisの前処理後に取得した、位置情報を保持するGLSLソース
     * @return 変換後のGLSL。対応構造と空き出力を確認できない場合は空
     */
    static Optional<String> adaptCutoutCoverage(String source) {
        var t=tokens(source);
        if(!containsOnce(t,tokens("flat in int mat;"))||t.stream().noneMatch(x->x.text.equals("gbuffersLighting")))return Optional.empty();
        // 深度・サンプルマスクの上書きや副作用があるシェーダーは、追加深度出力の対象から除外する。
        if(t.stream().anyMatch(x->Set.of("gl_FragDepth","gl_SampleMask","early_fragment_tests","buffer").contains(x.text)
                ||x.text.startsWith("image")||x.text.startsWith("atomic")))return Optional.empty();
        for(int i=0;i+3<t.size();i++)if(t.get(i).text.equals("location")&&t.get(i+1).text.equals("=")){
            try{if(Integer.parseInt(t.get(i+2).text)>=7)return Optional.empty();}catch(NumberFormatException e){return Optional.empty();}
        }
        int main=-1;
        for(int i=1;i+3<t.size();i++)if(t.get(i-1).text.equals("void")&&t.get(i).text.equals("main")&&t.get(i+1).text.equals("(")){
            int end=matching(t,i+1,"(",")");if(t.get(end+1).text.equals("{")){if(main>=0)return Optional.empty();main=i;}
        }
        if(main<0)return Optional.empty();var name=t.get(main);var declaration=t.get(main-1);
        // 元のmainを呼ぶラッパーを追加する。元のdiscardが成立した断片は深度出力にも到達しない。
        return Optional.of(source.substring(0,declaration.start)+"layout(location=7) out float titaniumCutoutDepth;\n"
                +source.substring(declaration.start,name.start)+"titaniumCutoutBody"+source.substring(name.end)
                +"\nvoid main(){ titaniumCutoutBody(); titaniumCutoutDepth=gl_FragCoord.z; }\n");
    }

    /**
     * 対応する最終カラーの出力文を検証し、表示色を後段処理へ移すための限定変換を生成する。
     *
     * @param source Irisの前処理後に取得した、位置情報を保持するGLSLソース
     * @param rule 読み込み済みソースが従う既知のAAインターフェース規則
     * @return 最終カラーの限定変換を適用したGLSL。対象外の構造では空
     */
    public static Optional<String> adaptFinal(String source, Rule rule) {
        return adaptFinal(source,rule,false);
    }
    public static Optional<String> adaptNoiseFinal(String source, Rule rule) {
        return adaptFinal(source,rule,true);
    }
    /**
     * 対応する最終カラーの出力文を検証し、表示色を後段処理へ移すための限定変換を生成する。
     *
     * @param source Irisの前処理後に取得した、位置情報を保持するGLSLソース
     * @param rule 読み込み済みソースが従う既知のAAインターフェース規則
     * @param noiseOnly 最終ノイズだけを分離する変換を選ぶ場合はtrue
     * @return 最終カラーの限定変換を適用したGLSL。対象外の構造では空
     */
    private static Optional<String> adaptFinal(String source,Rule rule,boolean noiseOnly) {
        if(rule==Rule.BSL)return Optional.empty(); // このインターフェースは任意の最終カラー処理に非対応。
        var t=tokens(source);
        String helper=rule==Rule.SOLAS?"sharpenFilter":"SharpenImage";
        if(t.stream().noneMatch(x->x.text.equals(helper)) || t.stream().anyMatch(x->Set.of("TemporalAA","DoTAA","discard","gl_FragDepth","buffer").contains(x.text)
                ||x.text.startsWith("image")||x.text.startsWith("atomic")))return Optional.empty();
        int main=-1, declaration=-1;
        for(int i=1;i+3<t.size();i++)if(t.get(i-1).text.equals("void")&&t.get(i).text.equals("main")&&t.get(i+1).text.equals("(")){
            int end=matching(t,i+1,"(",")");if(t.get(end+1).text.equals("{")){if(main>=0)return Optional.empty();main=end+1;declaration=i-1;}
        }
        if(main<0)return Optional.empty();int end=matching(t,main,"{","}");
        int last=end-1;if(!t.get(last).text.equals(";"))return Optional.empty();
        int first=last;while(first>main+1&&!Set.of(";","{","}").contains(t.get(first-1).text))first--;
        int equals=-1;for(int i=first;i<last;i++)if(t.get(i).text.equals("=")){if(equals>=0)return Optional.empty();equals=i;}
        if(equals<0)return Optional.empty();
        String lhs=String.join("",t.subList(first,equals).stream().map(Token::text).toList());
        String rhs=String.join("",t.subList(equals+1,last).stream().map(Token::text).toList());
        boolean rgb=lhs.endsWith(".rgb");String output=rgb?lhs.substring(0,lhs.length()-4):lhs;
        // Iris rewrites the legacy output to a declared single vec4, not an arbitrary local.
        boolean declared=containsOnce(t,tokens("out vec4 "+output+";"));
        if(!declared||!(rgb&&rhs.equals("color")||!rgb&&Set.of("vec4(color,1.0)","vec4(color,1.0f)","vec4(color,1.0F)").contains(rhs)))return Optional.empty();
        long outputs=0;for(int i=0;i+1<main;i++)if(t.get(i).text.equals("out")&&t.get(i+1).text.equals("vec4"))outputs++;
        if(outputs!=1)return Optional.empty();
        String sampler=rule==Rule.SOLAS?"colortex1":"colortex3";
        if(!containsOnce(t,tokens("uniform sampler2D "+sampler+";")))return Optional.empty();
        String raw="textureLod("+sampler+", texCoord, 0.0)";
        var result=new StringBuilder(source);
        if(noiseOnly){
            if(rgb)return Optional.empty();int noiseEnd=first-1,noiseStart=noiseEnd;
            while(noiseStart>main+1&&!Set.of(";","{","}").contains(t.get(noiseStart-1).text))noiseStart--;
            if(noiseEnd-noiseStart<4||!t.get(noiseStart).text.equals("color")||!t.get(noiseStart+1).text.equals("+")||!t.get(noiseStart+2).text.equals("="))return Optional.empty();
            var rhsTokens=t.subList(noiseStart+3,noiseEnd);
            if(rhsTokens.stream().anyMatch(x->!Set.of("vec3","dither","(",")","+","-","*","/",".",",").contains(x.text)
                    &&!x.text.matches("[0-9]+(?:\\.[0-9]*)?(?:[eE][+-]?[0-9]+)?[fF]?")))return Optional.empty();
            for(int n=1;n<rhsTokens.size();n++)if(Set.of("+","-").contains(rhsTokens.get(n).text)
                    &&rhsTokens.get(n).text.equals(rhsTokens.get(n-1).text))return Optional.empty();
            boolean sample=containsOnce(t,tokens("float dither = textureLod(noisetex,"))||containsOnce(t,tokens("float dither = texture2DLod(noisetex,"));
            if(!sample)return Optional.empty();
            String expression=source.substring(t.get(noiseStart+3).start,t.get(noiseEnd).start);
            result.insert(t.get(end).start,"\nif ("+FINAL_RAW+") "+lhs+" = vec4(titaniumEnhancedBeforeNoise, 1.0);\nelse if ("+FINAL_NOISE+") "+lhs+" = vec4("+raw+".rgb + titaniumEnhancedNoise, 1.0);\n");
            result.insert(t.get(noiseStart).start,"vec3 titaniumEnhancedBeforeNoise = color;\nvec3 titaniumEnhancedNoise = "+expression+";\n");
            result.insert(t.get(declaration).start,"uniform bool "+FINAL_RAW+";\nuniform bool "+FINAL_NOISE+";\n");
            return Optional.of(result.toString());
        }
        result.insert(t.get(end).start,"\nif ("+FINAL_RAW+") "+lhs+" = "+raw+(rgb?".rgb":"")+";\n");
        result.insert(t.get(declaration).start,"uniform bool "+FINAL_RAW+";\n");
        return Optional.of(result.toString());
    }

    /**
     * ジッターとメインTAA呼び出しの構造を検証し、AA担当をuniformで選択できる限定変換を適用する。
     *
     * @param source Irisの前処理後に取得した、位置情報を保持するGLSLソース
     * @param vertex 頂点シェーダーを変換する場合はtrue
     * @param rule 読み込み済みソースが従う既知のAAインターフェース規則
     * @return 変換後ソースと、ジッター・主表示色へ介入したかを示す結果
     */
    public static Result adapt(String source, boolean vertex, Rule rule) {
        if (source == null || source.length() > 2_000_000) throw new IllegalArgumentException("Shader source bound");
        var t = tokens(source);
        if (t.stream().anyMatch(x -> x.text.startsWith("titaniumEnhanced")))
            throw new IllegalArgumentException("Reserved Enhanced identifier collision");
        var edits = new ArrayList<Edit>(); boolean jitter = false, colour = false;
        String aa = rule == Rule.COMPLEMENTARY ? "DoTAA" : "TemporalAA";
        // 引数型が一致する関数定義だけを対象とし、宣言・コメント・呼び出しは除外する。
        for (int i = 1; i + 10 < t.size(); i++) {
            if (!t.get(i).text.equals("TAAJitter") || !t.get(i-1).text.equals("vec2") || !t.get(i+1).text.equals("(")) continue;
            int close = matching(t, i+1, "(", ")");
            if (!t.get(close+1).text.equals("{")) continue;
            if (jitter || close != i+7 || !t.get(i+2).text.equals("vec2") || !t.get(i+4).text.equals(",") || !t.get(i+5).text.equals("float"))
                throw new IllegalArgumentException("Unknown jitter signature");
            matching(t,close+1,"{","}");
            String coord=t.get(i+3).text, w=t.get(i+6).text;
            // The certified Complementary ScreenToView path consumes Iris's already-jittered inverse.
            // Removing that sample again in screen space applies it twice. Unknown uses fail closed.
            boolean inverseOwnsSample=!vertex && rule==Rule.COMPLEMENTARY;
            if(inverseOwnsSample)requireInverseReconstruction(t);
            if(!vertex && rule==Rule.SOLAS) requireNoFragmentJitterCalls(t);
            if(!vertex && rule==Rule.BSL)requireDiagonalReconstruction(t);
            edits.add(new Edit(t.get(i-1).start,"uniform bool "+OWN+";\nuniform vec2 "+CLIP+";\n"));
            edits.add(new Edit(t.get(close+1).end,"\nif ("+OWN+") return "+coord+(vertex || inverseOwnsSample ? ";\n" : " + "+CLIP+" * "+w+";\n")));
            jitter=true;
        }
        // 断片シェーダーのmain直下にある既知のTAA呼び出しを探し、表示色だけの担当を切り替える。
        if (!vertex) {
            int main=-1;
            for(int i=1;i+3<t.size();i++) if(t.get(i).text.equals("main") && t.get(i-1).text.equals("void") && t.get(i+1).text.equals("(")) {
                int c=matching(t,i+1,"(",")"); if(t.get(c+1).text.equals("{")) { if(main>=0)throw new IllegalArgumentException("Multiple main definitions"); main=c+1; }
            }
            if(main>=0) {
                int end=matching(t,main,"{","}"), depth=0;
                for(int i=main+1;i<end;i++) {
                    String s=t.get(i).text;
                    if(s.equals("{"))depth++; if(s.equals("}"))depth--;
                    if(!s.equals(aa) || !t.get(i+1).text.equals("("))continue;
                    if(colour || depth!=0 || !t.get(i+2).text.equals("color") || !t.get(i+3).text.equals(","))
                        throw new IllegalArgumentException("Unknown primary TAA call shape");
                    int callEnd=matching(t,i+1,"(",")");
                    if(!t.get(callEnd+1).text.equals(";"))throw new IllegalArgumentException("TAA call is not a complete statement");
                    int first=i; while(first>main+1 && !Set.of(";","{","}").contains(t.get(first-1).text))first--;
                    // Solas assigns returned history; Complementary writes it through an out parameter.
                    if(rule!=Rule.COMPLEMENTARY && !(first+2==i && t.get(first).text.equals(rule==Rule.BSL?"prev":"previousColor") && t.get(first+1).text.equals("=")))
                        throw new IllegalArgumentException("Unknown Solas history assignment");
                    if(rule==Rule.COMPLEMENTARY && first!=i)throw new IllegalArgumentException("Unknown Complementary TAA statement");
                    String args=String.join("",t.subList(i+2,callEnd).stream().map(Token::text).toList());
                    if(!args.equals(rule==Rule.SOLAS?"color,previousColor.r,z1":rule==Rule.BSL?"color,prev.r":"color,temp,z1"))
                        throw new IllegalArgumentException("Unknown primary TAA arguments");
                    if(rule==Rule.BSL)requireUnshiftedPrimarySample(t);
                    // 呼び出し形だけで採用せず、対応する関数定義と引数の意味が既知の構造であることも確認する。
                    requireAaDefinition(t,rule);
                    int voidToken=main-1;while(voidToken>=0&&!t.get(voidToken).text.equals("void"))voidToken--;
                    if(voidToken<0)throw new IllegalArgumentException("No main declaration");
                    edits.add(new Edit(t.get(voidToken).start,"uniform bool "+COLOUR+";\n"));
                    // TAA前の表示色を保存する。TAA本体は実行して履歴出力を更新し、必要時だけ表示色を戻す。
                    edits.add(new Edit(t.get(first).start,"vec3 titaniumEnhancedBeforeTaa = color;\n"));
                    edits.add(new Edit(t.get(callEnd+1).end,"\nif ("+COLOUR+") color = titaniumEnhancedBeforeTaa;\n"));
                    colour=true;
                }
            }
        }
        // 挿入位置が後続編集でずれないよう、元ソースの末尾側から順に編集する。
        var result=new StringBuilder(source);edits.sort(Comparator.comparingInt(Edit::offset).reversed());
        for(var e:edits)result.insert(e.offset,e.text);
        return new Result(result.toString(),jitter,colour);
    }

    /**
     * 指定したインターフェースに対応するTAA定義が一意に存在するか確認する。
     *
     * @param source Irisの前処理後に取得した、位置情報を保持するGLSLソース
     * @param rule 読み込み済みソースが従う既知のAAインターフェース規則
     * @return 指定したインターフェースに対応するTAA定義が一意に存在する場合はtrue
     */
    static boolean hasAaInterface(String source, Rule rule) {
        try { requireAaDefinition(tokens(source),rule); return true; }
        catch(IllegalArgumentException e) { return false; }
    }
    static boolean hasJitterInterface(String source) {
        var t=tokens(source);
        return signatureCount(t,"vec2 TAAJitter ( vec2 coord , float w ) {")==1;
    }
    /**
     * 指定したパック構造で、メインTAAへ色を渡す既知の呼び出しが存在するか確認する。
     *
     * @param source Irisの前処理後に取得した、位置情報を保持するGLSLソース
     * @param rule 読み込み済みソースが従う既知のAAインターフェース規則
     * @return 指定したパック構造で、メインTAAへ色を渡す既知の呼び出しが存在する場合はtrue
     */
    static boolean hasColourInterface(String source,Rule rule) {
        var t=tokens(source);
        if(rule==Rule.BSL)return containsOnce(t,tokens("vec2 newTexCoord = texCoord;"))
                &&containsOnce(t,tokens("vec3 color = texture2DLod(colortex1, newTexCoord, 0.0).rgb;"))
                &&containsOnce(t,tokens("prev = TemporalAA(color, prev.r);"))
                &&containsOnce(t,tokens("gl_FragData[0] = vec4(color, 1.0);"))
                &&containsOnce(t,tokens("gl_FragData[1] = vec4(prev);"));
        String input=rule==Rule.SOLAS?"vec3 color = texture2DLod(colortex1, texCoord, 0).rgb;"
                :"vec3 color = texelFetch(colortex3, texelCoord, 0).rgb;";
        String call=rule==Rule.SOLAS?"previousColor = TemporalAA(color, previousColor.r, z1);":"DoTAA(color, temp, z1);";
        String history=rule==Rule.SOLAS?"gl_FragData[1] = vec4(previousColor);":"gl_FragData[1] = vec4(temp, 1.0);";
        return containsOnce(t,tokens(input)) && containsOnce(t,tokens(call))
                && containsOnce(t,tokens("gl_FragData[0] = vec4(color, 1.0);")) && containsOnce(t,tokens(history));
    }
    /**
     * 指定のトークン列が対象内にちょうど一回だけ出現するか判定する。
     *
     * @param t 元ソース位置を持つGLSLトークン列
     * @param pattern 探索するトークンの連続列
     * @return 指定のトークン列が対象内にちょうど一回だけ出現する場合はtrue
     */
    private static boolean containsOnce(List<Token> t,List<Token> pattern) {
        int count=0;
        for(int i=0;i+pattern.size()<=t.size();i++){
            boolean same=true;for(int j=0;j<pattern.size()&&same;j++)same=t.get(i+j).text.equals(pattern.get(j).text);
            if(same)count++;
        }
        return count==1;
    }
    /**
     * 既知のTAA関数定義を確認し、混在または未知の引数構造を除外する。
     *
     * @param t 元ソース位置を持つGLSLトークン列
     * @param rule 読み込み済みソースが従う既知のAAインターフェース規則
     */
    private static void requireAaDefinition(List<Token> t,Rule rule) {
        String expected=rule==Rule.SOLAS?"vec4 TemporalAA ( inout vec3 color , float tempData , float z1 ) {"
                :rule==Rule.BSL?"vec4 TemporalAA ( inout vec3 color , float tempData ) {"
                :"void DoTAA ( inout vec3 color , inout vec3 temp , float z1 ) {";
        if(signatureCount(t,expected)!=1)throw new IllegalArgumentException("Unknown primary TAA interface");
        String other=rule==Rule.COMPLEMENTARY?"TemporalAA":"DoTAA";
        if(t.stream().anyMatch(x->x.text.equals(other)))throw new IllegalArgumentException("Mixed TAA interfaces");
    }
    /**
     * 指定した関数定義のトークン列と括弧の整合性を確認して出現数を数える。
     *
     * @param t 元ソース位置を持つGLSLトークン列
     * @param expected 対応契約として要求するバージョンまたはトークン列
     * @return 定義本体の括弧まで確認した、対応関数の出現数
     */
    private static int signatureCount(List<Token> t,String expected) {
        var pattern=expected.split(" ");int count=0;
        for(int i=0;i+pattern.length<=t.size();i++){
            boolean same=true;for(int j=0;j<pattern.length;j++)same &= t.get(i+j).text.equals(pattern[j]);
            if(same) {matching(t,i+pattern.length-1,"{","}");count++;}
        }
        return count;
    }
    private static void requireNoFragmentJitterCalls(List<Token> t) {
        for(int i=1;i+1<t.size();i++)if(t.get(i).text.equals("TAAJitter")&&t.get(i+1).text.equals("(")&&!t.get(i-1).text.equals("vec2"))
            throw new IllegalArgumentException("Uncertified Solas fragment jitter consumer");
    }
    /**
     * 主入力のUVが既知の無変位経路で使われていることを確認する。
     *
     * @param t 元ソース位置を持つGLSLトークン列
     */
    private static void requireUnshiftedPrimarySample(List<Token> t){
        if(!containsOnce(t,tokens("vec2 newTexCoord = texCoord;")))throw new IllegalArgumentException("Unknown primary coordinate");
        for(int i=0;i<t.size();i++)if(t.get(i).text.equals("newTexCoord")){
            boolean declaration=i>0&&t.get(i-1).text.equals("vec2");
            boolean sample=i>1&&t.get(i-1).text.equals(",")&&t.get(i-2).text.equals("colortex1");
            if(!declaration&&!sample)throw new IllegalArgumentException("Additional primary sampling offset is not certified");
        }
    }

    /**
     * 対角項を使う既知の逆投影とサンプル補正の呼び出し方を確認する。
     *
     * @param t 元ソース位置を持つGLSLトークン列
     */
    private static void requireDiagonalReconstruction(List<Token> t){
        List<Token> inverseBody=null;
        for(int i=1;i+2<t.size();i++)if(t.get(i).text.equals("ToNDC")&&t.get(i-1).text.equals("vec3")&&t.get(i+1).text.equals("(")){
            int c=matching(t,i+1,"(",")");if(!t.get(c+1).text.equals("{"))continue;
            if(inverseBody!=null||c!=i+4||!t.get(i+2).text.equals("vec3"))throw new IllegalArgumentException("Unknown diagonal inverse definition");
            int end=matching(t,c+1,"{","}");inverseBody=t.subList(c+2,end);
        }
        for(int i=1;i+2<t.size();i++)if(t.get(i).text.equals("TAAJitter")&&t.get(i+1).text.equals("(")&&!t.get(i-1).text.equals("vec2")){
            int end=matching(t,i+1,"(",")");
            if(i<4||!t.get(i-1).text.equals("(")||!t.get(i-2).text.equals("vec3")||!t.get(i-3).text.equals("(")||!t.get(i-4).text.equals("ToNDC"))
                throw new IllegalArgumentException("Unknown diagonal fragment jitter consumer");
            String args=String.join("",t.subList(i+2,end).stream().map(Token::text).toList());
            if(!args.matches("[A-Za-z_][A-Za-z0-9_]*\\.xy,-0\\.5[fF]?"))throw new IllegalArgumentException("Unknown diagonal jitter weight");
            if(inverseBody==null||inverseBody.stream().filter(x->x.text.equals("gbufferProjectionInverse")).count()!=4
                    ||!containsOnce(inverseBody,tokens("gbufferProjectionInverse[0].x"))||!containsOnce(inverseBody,tokens("gbufferProjectionInverse[1].y"))
                    ||!containsOnce(inverseBody,tokens("gbufferProjectionInverse[2].zw"))||!containsOnce(inverseBody,tokens("gbufferProjectionInverse[3]")))
                throw new IllegalArgumentException("Diagonal inverse interface unavailable");
        }
    }
    /**
     * Irisの逆投影にサンプル変位を任せる、既知のScreenToView呼び出しを確認する。
     *
     * @param t 元ソース位置を持つGLSLトークン列
     */
    private static void requireInverseReconstruction(List<Token> t) {
        boolean inverse=false;
        for(int i=1;i+2<t.size();i++){
            if(t.get(i).text.equals("ScreenToView") && t.get(i-1).text.equals("vec3") && t.get(i+1).text.equals("(")){
                int c=matching(t,i+1,"(",")");
                if(!t.get(c+1).text.equals("{"))continue;
                int end=matching(t,c+1,"{","}");
                inverse=t.subList(c+2,end).stream().anyMatch(x->x.text.equals("gbufferProjectionInverse"));
                if(!inverse)throw new IllegalArgumentException("Unknown ScreenToView inverse ownership");
            }
            if(!t.get(i).text.equals("TAAJitter") || !t.get(i+1).text.equals("("))continue;
            int c=matching(t,i+1,"(",")");
            if(t.get(i-1).text.equals("vec2"))continue; // 関数定義または宣言は呼び出しとして数えない。
            if(i<4 || !t.get(i-1).text.equals("(") || !t.get(i-2).text.equals("vec3")
                    || !t.get(i-3).text.equals("(") || !t.get(i-4).text.equals("ScreenToView"))
                throw new IllegalArgumentException("Unknown fragment jitter consumer");
            int comma=-1;for(int j=i+2;j<c;j++)if(t.get(j).text.equals(",")){
                if(comma>=0)throw new IllegalArgumentException("Unknown fragment jitter arguments");comma=j;
            }
            String weight=comma<0?"":String.join("",t.subList(comma+1,c).stream().map(Token::text).toList());
            if(!Set.of("-0.5","-0.5f","-0.5F").contains(weight))
                throw new IllegalArgumentException("Unknown reconstruction jitter weight: "+weight.substring(0,Math.min(80,weight.length())));
        }
        if(!inverse && t.stream().anyMatch(x->x.text.equals("ScreenToView")))
            throw new IllegalArgumentException("No certified fragment inverse definition");
    }
    /**
     * 開始括弧から深さを追跡し、対応する終了括弧のトークン位置を返す。
     *
     * @param t 元ソース位置を持つGLSLトークン列
     * @param start 対応括弧の探索を開始するトークン位置
     * @param open 対応探索を始める開き括弧のトークン
     * @param close 対応を探索する終了括弧のトークン
     * @return 対応する終了括弧のトークン位置
     */
    private static int matching(List<Token> t,int start,String open,String close) {
        int depth=0;for(int i=start;i<t.size();i++){if(t.get(i).text.equals(open))depth++;if(t.get(i).text.equals(close)){depth--;if(depth==0)return i;}}
        throw new IllegalArgumentException("Unbalanced shader delimiter");
    }
    /**
     * 空白・コメント・前処理行を除き、元ソース上の位置を保持したGLSLトークン列を生成する。
     *
     * @param s 解析または検証対象の文字列
     * @return 元文字列上の開始・終了位置を持つ字句トークン一覧
     */
    private static List<Token> tokens(String s) {
        if(s==null||s.length()>2_000_000)throw new IllegalArgumentException("Shader source bound");
        var out=new ArrayList<Token>();
        for(int i=0;i<s.length();) {
            char c=s.charAt(i);if(Character.isWhitespace(c)){i++;continue;}
            if(c=='#'){int e=s.indexOf('\n',i);i=e<0?s.length():e+1;continue;}
            if(c=='/'&&i+1<s.length()&&s.charAt(i+1)=='/'){int e=s.indexOf('\n',i);i=e<0?s.length():e+1;continue;}
            if(c=='/'&&i+1<s.length()&&s.charAt(i+1)=='*'){int e=s.indexOf("*/",i+2);if(e<0)throw new IllegalArgumentException("Unclosed comment");i=e+2;continue;}
            if(c=='"'||c=='\'')throw new IllegalArgumentException("Unexpected GLSL literal");
            int start=i++;
            if(Character.isLetterOrDigit(c)||c=='_')while(i<s.length()&&(Character.isLetterOrDigit(s.charAt(i))||s.charAt(i)=='_'))i++;
            out.add(new Token(s.substring(start,i),start,i));
            if(out.size()>400_000)throw new IllegalArgumentException("Shader token bound");
        }
        return out;
    }
}
