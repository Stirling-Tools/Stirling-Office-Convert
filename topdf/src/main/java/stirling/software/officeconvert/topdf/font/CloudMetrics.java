package stirling.software.officeconvert.topdf.font;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


/** Advance widths of Office cloud fonts, for laying text out as if the cloud font were installed. */
public final class CloudMetrics {

    public static final String CHARS = range(0x20, 0x7E) + range(0xA0, 0xFF)
            + "\u0152\u0153\u0160\u0161\u0178\u017d\u017e\u0192\u02c6\u02dc\u2013\u2014\u2018\u2019"
            + "\u201a\u201c\u201d\u201e\u2020\u2021\u2022\u2026\u2030\u2039\u203a\u20ac\u2122";

    private static final int NONE = 36 * 36 - 1;

    private static final short[] INDEX = index();

    private static final Map<String, CloudMetrics[]> TABLE = table();

    // Thousandths of an em for each character of CHARS, two base-36 digits each; zz marks a missing glyph.
    private final short[] advances;

    private CloudMetrics(String advances) {
        this.advances = new short[CHARS.length()];
        for (int i = 0; i < this.advances.length; i++) {
            this.advances[i] = (short) Integer.parseInt(advances, 2 * i, 2 * i + 2, 36);
        }
    }

    /** The advance table of a cloud font family and style, or null when the family is not one. */
    public static CloudMetrics of(String family, boolean bold, boolean italic) {
        CloudMetrics[] styles = TABLE.get(FontLibrary.normalize(family));
        if (styles == null) {
            return null;
        }
        int wanted = (bold ? 2 : 0) + (italic ? 1 : 0);
        for (int style : new int[] {wanted, wanted & 1, wanted & 2, 0}) {
            if (styles[style] != null) {
                return styles[style];
            }
        }
        return null;
    }

    /** The advance in ems, or NaN when the cloud font has no glyph for it. */
    public float advance(int codePoint) {
        int i = codePoint >= 0 && codePoint < INDEX.length ? INDEX[codePoint] : -1;
        return i < 0 || advances[i] == NONE ? Float.NaN : advances[i] / 1000f;
    }

    private static String range(int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int c = from; c <= to; c++) {
            b.append((char) c);
        }
        return b.toString();
    }

    private static short[] index() {
        int top = 0;
        for (int i = 0; i < CHARS.length(); i++) {
            top = Math.max(top, CHARS.charAt(i));
        }
        short[] index = new short[top + 1];
        Arrays.fill(index, (short) -1);
        for (int i = 0; i < CHARS.length(); i++) {
            index[CHARS.charAt(i)] = (short) i;
        }
        return index;
    }

    private static void put(Map<String, CloudMetrics[]> m, String family, int style, String advances) {
        m.computeIfAbsent(FontLibrary.normalize(family), k -> new CloudMetrics[4])[style] = new CloudMetrics(advances);
    }

    private static Map<String, CloudMetrics[]> table() {
        Map<String, CloudMetrics[]> m = new HashMap<>();
        put(m, "Aptos", 0,
                "5n85aaexeumyhv5u8585cpeu7y9g7y9feueueueueueueueueueu7y7yeueueudxovgdgsj8j2fgekjojn7897fsdwlyjmkcg1kc"
                + "gufqdbixg9osfdf0ec869f86eucsfcerflelflen8ddgfb6n6ndj78npfbfcflfl9adi8zfjckk1cackc6867i86eu5n7zeueueu"
                + "eu7id2fcktbxbkeu9gd0fca5eu9g9gfcfmfk7yfc9gcfbkjpl5lndxgdgdgdgdgdgdpxj8fgfgfgfg78787878j2jmkckckckckc"
                + "eukcixixixixf0g1f1erererererernhelenenenen6n6n6n6nfcfbfcfcfcfcfceufcfjfjfjfjckflcktgotfqdif0ecc68dfc"
                + "fccspk7i7i7ickclclcncnd2mqw16p6peuel");
        put(m, "Aptos", 1,
                "5n859zexeuoaic5i8585aleu7i9g7z9feueueueueueueueueueu7z7ieueueudxopgdgyjdj1fgf3jojn7897fldvlyjmkcgfkc"
                + "gufqdbixg8otfdf0e5849f81eucsfcesfleqfleq80dgfb6f6fdk78nqfbfcflfk99dh8pfbckk1cackc68v7i8veu5n85eueueu"
                + "eu7id5fcktbxaxeu9gd9fca5eu9g9gfcfnfg7zfc9gcfaxkemdnodxgdgdgdgdgdgdopjdfgfgfgfg78787878j1jmkckckckckc"
                + "eukcixixixixf0gefeesesesesesesnieqeqeqeqeq6m6m6m6mfcfbfcfcfcfcfceufcfbfbfbfbckflckthosfqdhf0e5c680fc"
                + "fccspk7i7i7ickckclc5c5d2njxk6262eueo");
        put(m, "Aptos", 2,
                "5n85boexeundit6g8585cpeu8c9g8careueueueueueueueueueu8c8ceueueueep0h8h7jnjnfxexjyk986a7hbe8mtk3khgtkh"
                + "hmgme1jch5q4gngaf79dar9deucsfcfcgaf9gafg9be7g37g7geu88opg3fxgagaa9ec9vg6dql5drdqcy9d7i9deu5n8ceueueu"
                + "eu7idvfcktcecdeu9gcnfcaieu9y9yfcg9h78cfc9ycxcdl5m0mieeh8h8h8h8h8h8qpjnfxfxfxfx86868686jnk3khkhkhkhkh"
                + "eukhjcjcjcjcgagtfmfcfcfcfcfcfco8f9fgfgfgfg7g7g7g7gfxg3fxfxfxfxfxeufxg6g6g6g6dqgadqszp7gmecgaf7cy9bfc"
                + "fccspk8a8a8aefegegdododknmwx7070euf1");
        put(m, "Aptos", 3,
                "5n85b8exeupbj95z8585bxeu8a9g8careueueueueueueueueueu8c8aeueueuecoxh8hgjsjnfxfgjyk986a6gze8mtk3khh8kh"
                + "hmgme2jch4q5gngaf096ar8xeucsfcfhgbffgbfl91e7g37e7eer88opg3fxgbg9a6ec9lg4dql5drdqcy9m7i9meu5n85eueueu"
                + "eu7ie4fcktcebkeu9gcwfcaieu9y9yfcgagu8cfc9ycwbkm0nkoyech8h8h8h8h8h8pdjsfxfxfxfx86868686jnk3khkhkhkhkh"
                + "eukhjcjcjcjcgah7g0fhfhfhfhfhfho2ffflflflfl7g7g7g7gfxg3fxfxfxfxfxeufxg4g4g4g4dqgbdqszp6gmecgaf0cy91fc"
                + "fccspk8a8a8aefefegd6d6dknwz16767euf9");
        put(m, "Aptos Display", 0,
                "5d6uaaeoejmxhb5u8686alej6u9p6u9fejejejejejejejejejej6u6uejejejdioeg4g7i8i7f1e5inie6j8tfedgl9ixjbfgjb"
                + "gef6d9i3fyomfaevec869f86ejcsfcdlefdpefdq7vc7e25u5ucf6sm8e2e9efef8mcv8ge2c1jwc8c1bu866386ej5d6uejejej"
                + "ej63cefcktbxb0ej9pcgfca5ej9g9gfcegf56ufc9gcfb0julcm3dig4g4g4g4g4g4psi8f1f1f1f16j6j6j6ji7ixjbjbjbjbjb"
                + "ejjvi3i3i3i3evfgehdldldldldldlm8dpdqdqdqdq5u5u5u5ue8e2e9e9e9e9e9eje9e2e2e2e2c1efc1slnef6cvevecbu7vfc"
                + "fccspk6k6k6kbmbnbncncnd2lmw06565ejev");
        put(m, "Aptos Display", 1,
                "5d6u9zeoejoehd5v8686alej6w9o6u9fejejejejejejejejejej6w6xejejejdio9g4g2idief1e5inie6j8tfedgl9ixjbfijb"
                + "g6f6d9i3fyomfaeveb869c86ejcsfcdlefdnefdn7vc7e25u5ucf6sm2e2e9eged8mcv8ge2c1jwc8c1bu866886ej5d6uejejej"
                + "ej68cofcktbxb0ej9ocpfca5ej9g9gfce6f66ufc9gcfb0k1lim3dig4g4g4g4g4g4ofidf1f1f1f16j6j6j6jieixjbjbjbjbjb"
                + "ejjbi3i3i3i3evfkebdldldldldldlludndndndndn5x5x5x5xe8e2e9e9e9e9e9eje9e2e2e2e2c1egc1sjncf6cvevebbu7vfc"
                + "fccspk6y6y6wc1c1bxcncnd2lnxo6565ejev");
        put(m, "Aptos Display", 2,
                "5d79boflf0nbi76g9d9dbxf079a579arf0f0f0f0f0f0f0f0f0f07979f0f0f0dyoogygniriuf9edj2iz7f9pgrdom2jcjlg9jl"
                + "gyfydvimgpplggfwf79dar9df0csfce3f2e4f2eb8ud4et6o6odw7rnceteqf2f29jdg99eud7ksdld7ci9d6z9df05d79f0f0f0"
                + "f06zcxfcktcebtf0a5bzfcaif09y9yfcfdgt79fc9ycwbtljmfn0dygygygygygygyqbirf9f9f9f97f7f7f7fiujcjljljljljl"
                + "f0k5imimimimfwg9f3e3e3e3e3e3e3mle4ebebebeb6o6o6o6oeteteqeqeqeqeqf0eqeueueueud7f2d7rynkfydgfwf7ci8ufc"
                + "fccspk7k7k7kdqdrdrdododkmkwv6g6gf0fb");
        put(m, "Aptos Display", 3,
                "5d79b8flf0pmig6g9d9dbxf07aa479arf0f0f0f0f0f0f0f0f0f07a7df0f0f0dzoggyghiviwf9edj1iz7f9ogrdom2jcjlg9jl"
                + "gufydvilgppkggfwf49dah9df0csfce8f2e8f2ee8ud4et6o6odw7rnaeteqf2f19jdg99etd7ksdld7ci9d7g9df05d79f0f0f0"
                + "f07gdbfcktcebtf0a4c9fcaif09y9yfceygu79fc9ycwbtlqmmn0dzgygygygygygyp1ivf9f9f9f97f7f7f7fiwjcjljljljljl"
                + "f0jlililililfwgff5e8e8e8e8e8e8mae8eeeeeeee6q6q6q6qeteteqeqeqeqeqf0eqetetetetd7f2d7rsnhfydgfwf4ci8wfc"
                + "fccspk7q7q7adwdwdkdndodkmkzc6g6gf0fb");
        put(m, "Aptos Light", 0,
                "5n859lexeumqhe5k8585cpeu7r9g7r8seueueueueueueueueueu7r7reueueudposfygkj1irf8eejkjc6s8pf1dpljjekafnka"
                + "ggfacxipfuo4eqecdw7l8s7leucsfcehf8e9f8e87wd3ex6868cv6qn7exf1f8f88td38jf7bzjgbkbzbs7l7i7leu5n7seueueu"
                + "eu7icofcktbpb5eu9gd7fc9zeu9797fcfbeq7rfc97c6b5j0kql8dpfyfyfyfyfyfypjj1f8f8f8f86s6s6s6sirjekakakakaka"
                + "eukaipipipipecfneqehehehehehehn3e9e8e8e8e868686868f1exf1f1f1f1f1euf1f7f7f7f7bzf8bztpolfad3ecdwbs7wfc"
                + "fccspk747474bnbnbnc5c5ctmavl6k6keued");
        put(m, "Aptos Light", 1,
                "5n859cexeunrhw5a85859xeu749g7s8seueueueueueueueueueu7s74eueueudpolfygqj6iqf8exjjjc6r8pevdoljjekag0ka"
                + "ggfacyipfto4eqecdp7l8s7leucsfceff7eef7eb7id3ex5y5ycz6qn9exf1f8f78td28aexbzjgbkbzbs8i7i8ieu5n85eueueu"
                + "eu7icofcktbpaleu9gdefc9zeu9797fcfceq7sfc97c6aljmlsn0dpfyfyfyfyfyfyodj6f8f8f8f86r6r6r6riqjekakakakaka"
                + "eukaipipipipecg0f2efefefefefefn7eeebebebeb67676767f1exf1f1f1f1f1euf1exexexexbzf8bztqolfad2ecdpbs7ifc"
                + "fccspk747474bnbnbnbnbnctncwt6060euee");
        put(m, "Aptos Narrow", 0,
                "57929fdue3l5gc5c8g8gdue3788i788pe3e3e3e3e3e3e3e3e3e37878e3e3e3cnmeeufdhlhfe5dchyhy6r8heicpk2hxijepij"
                + "ffefc4h9ermle1dpd27j8p7je3bkdvdjeadbeadh7qcie36868cg6rlse3e1eaea8lcb88e6bii8babib37jcu7je35792e3e3du"
                + "e3cubxdviubcaje38ibtdva5e39595e2ecec79dv95bnajj2kgkscneueueueueueunlhle5e5e5e56r6r6r6rhfhxijijijijij"
                + "e3ijh9h9h9h9dpepdtdjdjdjdjdjdjledbdhdhdhdh68686868e0e3e1e1e1e1e1e3e1e6e6e6e6bieabiqqmhefcbdpd2b37qdv"
                + "dvbkn46v6v6vbwbwbwbjbjbvm0tf6464e3da");
        put(m, "Aptos Narrow", 1,
                "579296due3m9gr518g8gdue36v8j798ne3e3e3e3e3e3e3e3e3e3796we3e3e3cqmmevfihohde3drhyhy6r8heacmk1hwijf1ij"
                + "fdecc5h9eqmle1dpcv7g8o7de3bkdvdheadfeedh7dc9e25z5zcg6llpe2e0eaec8kca7ze2bii8babib383cu84e35792due3du"
                + "e3duc7dvitav9xe38jc3dva5e39595dveheb79dv95b99yjslbmjcqevevevevevevm0hoe3e3e3e36r6r6r6rhdhwijijijijij"
                + "e3ijh9h9h9h9dpf0e3dhdhdhdhdhdhlbdfdhdhdhdh6c6c6c6cdxe2e0e0e0e0e0e3dxe2e2e2e2bieabiqnmgeccadpcvb37ddv"
                + "dvbkn46v6v6vbwbwbwb8b3bvlmum5h5ie3dc");
        put(m, "Aptos Narrow", 2,
                "5192ahdue3kwgy5r8j8jdue37f8i7f9ue3e3e3e3e3e3e3e3e3e37f7fe3e3e3crlafdfihmhlebddhti57k99fmcrkehyiaf4ia"
                + "fteycih6f9ncexemdl8g9u8ge3b9dhdveqdseqe28gctel6z70de7jmeeledeqeq9ccr8velcditcccdbl8gcy8ge35192e3e3du"
                + "e3cycfdhibbib2e38ibbdhahe39i9ieeeofn7idh9ibvb2k7lglmcrfdfdfdfdfdfdnqhmebebebeb7k7k7k7khlhyiaiaiaiaia"
                + "e3iah6h6h6h6emf4e0dvdvdvdvdvdvlpdse2e2e2e26z6z6z6ze9elededededede3edelelelelcdeqcdpnmfeycremdlbl8gdh"
                + "dhb9mh7f7f7fdgdhdhc0c0c0mntd6a6ae3dg");
        put(m, "Aptos Narrow", 3,
                "5192a3due3m0h45c8j8jdue37f8j7f9je3e3e3e3e3e3e3e3e3e37f7fe3e3e3ctm7f9fmhjhge4dphoi07g96f8clk9hti4fci4"
                + "foetcih5f5n8evejdc889o80e3b9dhdsemdreqdw84cmei6q6qda7dm0eie7eneq97cq8leic9itcdc9bi8kcy8ke35192due3du"
                + "e3ductdhibb0aae38jbidhahe39i9idherfa7fdh9ibfaakpm8n2ctf9f9f9f9f9f9m1hje4e4e4e47g7g7g7ghghti4i4i4i4i4"
                + "e3i4h5h5h5h5ejfbebdsdsdsdsdsdslddrdwdwdwdw70707070e4eie7e7e7e7e7e3e4eieieieic9enc9pgm7etcqejdcbi84dh"
                + "dhb9mh7f7f7fdgdgdhbxbrc1lyuf5g5he3dj");
        put(m, "Arial Nova Light", 0,
                "7a997qfgfgo6ij4p9999atfl7q997q7qfgfgfgfgfgfgfgfgfgfg7q7qflflfldwojhyhtjrjgg6f0ksja6md9gwermxjblhgrlh"
                + "hlgzfgjbgzo6gzgkg9997q99d1dw99eofdegfdeo75faev5j5ndn5jmfeveyfdfd9cd183evd9jjd7d0cm996699fl7a99fgfgfg"
                + "fg66f299m89tfgfl99m8dw9kfl999999f1f27q9999agfgm3m3m3dwhyhyhyhyhyhyr3jrg6g6g6g66m6m6m6mjgjblhlhlhlhlh"
                + "fllhjbjbjbjbgkgreleoeoeoeoeoeonoegeoeoeoeo5j5j5j5jeyeveyeyeyeyeyfleyevevevevd0fdd0shozgzd1gkg9cmfg99"
                + "99dwrs666666atatatf2f2a7rsyh9p9pfglh");
        put(m, "Avenir Next LT Pro", 0,
                "6y94b9fgg4n5jk788c8cccii788w78aag4g4g4g4g4g4g4g4g4g48c8ciiiiiidem8jghok0l1ggfmlnjy78dohge6oml8nmg4nf"
                + "gnfofujqhbr0i1gqfw8caa8ciidw6oeuhpdwhpfw87hkg76y6ze670ojg5gzhnhna0cc8tg5dkkqdgdkca8c668cii6y94g4g4g4"
                + "g466g46om8a0deii8wgo6ob4iiaaaa6ofggo786oaaa0dencncncdejgjgjgjgjgjgrik0gggggggg78787878l1l8nmnmnmnmnm"
                + "iinmjqjqjqjqgqg4gleueueueueueuo6dwfwfwfwfw6y6y6y6ygzg5gzgzgzgzgziigzg5g5g5g5dkhldkrkrmfoccgqfwcag46o"
                + "6ob4go666666arararfgfgdwrsrs8r8rg4rs");
        put(m, "Avenir Next LT Pro", 2,
                "6y9xdugrhypblp8f9i9idlii8c928cbjhyhyhyhyhyhyhyhyhyhy9898iiiiiif6m8k9ihjcligvg0ldl88uepjremqjlynnhsnt"
                + "ifgfg5kiiyshjjilhn9ibj9ij6dw8cfqibdziagka4iah5888bgj8apoh4hki9i9b0d4ach2fro7gifre89i6p9iii6y9xhyhyhy"
                + "hy6pgv8cm8aof3ii92go8cbkiibobo8ciih68c8cboaof3q4q4q4f6k9k9k9k9k9k9spjcgvgvgvgv8u8u8u8ulilynnnnnnnnnn"
                + "iinnkikikikiilhsijfqfqfqfqfqfqozdzgkgkgkgk88888888hkh4hkhkhkhkhkiihkh2h2h2h2fribfrsbr1gfd4ilhne8hy8c"
                + "8cb4go8c8c8ce3e3e3grgrdwrsyl8u8uhyrs");
        put(m, "Avenir Next LT Pro Light", 0,
                "6y8ob9fefyn1jk788c8cccii728q72aafyfyfyfyfyfyfyfyfyfy8686iiiiiidbm8j6hhk1l5gffllsk270dkgxe2oslcntg2nr"
                + "gmfqfljxh6qvhjg7g38caa8ciidw6oenhmdqhmfr83hig36r6sdn6solg2gxhkhk9nc38pg2d4kcd6d2c98c668cii6y8ofyfyne"
                + "fy66fy6om89xdeii8qgo6ob4iia4a46ofggo726oa49xden0n0n0dbj6j6j6j6j6j6rbk1gfgfgfgf70707070l5lcntntntntnt"
                + "iintjxjxjxjxg7g2gjeneneneneneno9dqfrfrfrfr6r6r6r6rgxg2gxgxgxgxgxiigxg2g2g2g2d2hjd2rgrrfqc3g7g3c9fy6o"
                + "6ob4go656565agagagf5f5dwrsrs8r8rfyrs");
        put(m, "Bierstadt", 0,
                "637ya0epf3nkhm5v8787amf37y9p7y9gf3f3f3f3f3f3f3f3f3f37y7yf3f3f3dxovgegsj8iyfhekjpjo7999ftdwlzjnkcg2kc"
                + "gufrdbiygaoufef1ed879g87cdcsfcesflemfmen8edhfc6n6ndk79nqfcfcflfm9bdj90fjclk2cbclc6876987f3637zf3f3f3"
                + "f369d3fcktbyc7f39pdtfca6f39g9gfcfndg7yfc9gcfc7mwmvmwdxgegegegegegeplj8fhfhfhfh79797979j1jnkckckckckc"
                + "f3kciyiyiyiyf1g2f2esesesesesesnfemenenenen6n6n6n6nfcfcfcfcfcfcfcf3fcfjfjfjfjclfmcltgosfrdjf1edc6f3fc"
                + "fccspk7j7j7jcmcmcmekekd2mrwn6262f3ey");
        put(m, "Bierstadt", 1,
                "637za0epfrowhu5j8787amdt7j9p7z9gf3f3f3f3f3f3f3f3f3f37z7jdtdtdtdyopgegyjej1fhf3jpjo7998fteflzjmkcggkc"
                + "gufrdcixg9oufef1e5859g82cdcsfceufmesfles81dhfb6969ds79nrfcfcfmfk9adi8qfcclk2cbclc68w698wfc637zemdwdt"
                + "f169defcktbyaxdt9pdtfca6dt9g9gfcfmdg7zfc9gcfaxnpnunpdxgegegegegegeoqjefhfhfhfh79797979j1jmkckckckckc"
                + "dtkdixixixixf1gffeeueueueueueunkeseseseses6n6n6n6nfcfcfcfcfcfcfcdtfcfcfcfcfcclfmclthotfrdif1e5c6b1fc"
                + "fccspk7d7j7jcmcmcmeiejd2njy66262irep");
        put(m, "Bierstadt", 2,
                "708dbbfnflo3il6h9g9gc0fl8da68dauflflflflflflflflflfl8d8dflflflefp0hah8jojkfyeyjyka88aahee9muk4kigvki"
                + "hngpe3jdh7q8gqgdf99gau9gdhcsfcfdgcfbgcfg9de9g57i7iex8aosg5fzgcgcabee9xg7dtl8dvdtcz9g7j9gfl708dflflfl"
                + "fl7jdwfcktcfd7fla6dtfcajfl9z9zfcgbfq8dfc9zcxd7npnpnpefhahahahahahaq9jofyfyfyfy88888888jok4kikikikiki"
                + "flkijdjdjdjdgdgvfnfdfdfdfdfdfdo2fbfgfgfgfg7i7i7i7ifzg5fzfzfzfzfzflfzg7g7g7g7dtgcdtsyp6gpeegdf9czflfc"
                + "fccspk8b8b8bejekekg1g1dlnpxo6767flfj");
        put(m, "DM Sans", 0,
                "7d6w7wmmg2lhkc4dacacdag74ye95favj98efwgcgth0hietgyhi5j67dveldvecs5iggqk0jcfoeul1iv6gdvg6elnjj5lsg3lx"
                + "ghg2fki8ikqvgqg3ev8jav8jh8hu72f1hgfuhgft99fcfw6n6qe066ovfwgghghga3dwarfwehkydufgckbt6gbte47d6wfugoes"
                + "gp6gg88ym2cgckicgoe0aw9jgr969672g7ga5f7k5oc1ckhdiqjwecigigigigigigpbk0fofofofo6g6g6g6gjxj5lslslslsls"
                + "drlui8i8i8i8g3g3igf1f1f1f1f1f1pdfuftftftft66666666hefwggggggggggekgkfwfwfwfwfghgfgv2r7g2dwg3evck998u"
                + "9lhtni5w5x54aaaa9ieres9yfnv77m7mljlg");
        put(m, "Grandview Display", 0,
                "7h7jaihcg1j3i44y9f9fbyeo6bdb6baieseseseseseseseseses6b6bbscrbxc0oqhzhyh7i8gmfjhyiv7ne1hufxlnjghyhbi9"
                + "i4h4ddi7gbnjfrdzf486ai86dzc486e9ekdzeged89egez736xe47hn9ezelekegbbdv8jexdckyducvd7a07na0ea7h7jduggfz"
                + "dz7ng0asje9kdyeidbjec3a4eo8o9c86gjj55w7w6r9idyj0iujkbghzhzhzhzhzhzr5h7gmgmgmgm7n7n7n7nivjghyhyhyhyhy"
                + "dzhyi7i7i7i7dzhbewe9e9e9e9e9e9n6dzedededed6z6z6z6zefezeleleleleldeelexexexexcvekcvrunmh4dvdzf4d78faz"
                + "blfins4y4y6b8v8v8va8a87pg7qk8m8mhiif");
        put(m, "Lora", 0,
                "7b7e9mn2epo6jb5s8h8hegeq71dl6uj6h9a4exfhf2f5g2ctfmg26y7beqeqeqd6m4hehajfkphaf8l5lo9badizg2pil6m2gum4"
                + "hgfrhskwi5rni1hmgy9zj69zmvk970ecg6esgmf29efdgn7t5wex7kougvg9glg0c1cvargle1m9eqeoe79a6f9aeq7b6rerf8ev"
                + "jl6retarodbdeqeq85oe9vb9eqapaa70h2dx6y847vbneql1ltm4czheheheheheheqdjfhahahaha9b9b9b9bkql6m2m2m2m2m2"
                + "eqm2kwkwkwkwhmgkixececececececllesf2f2f2f27t7t7t7tfjgvg9g9g9g9g9eqg9glglglgleog1eoseojfrcvhmgye79jb4"
                + "bagend684u71b49pbxe8erb5luzvananl4to");
        put(m, "Lora", 1,
                "7b7k9wmgepo9iz5j8a8ae9eq6ldf6gi6gl9uelf5enejfeckfofc6y7jeqeqeqd5m0hjh7j2kih7f1kxlh99a7ijg1phl6lwgllw"
                + "hdfqhokoihrmi4hth0a1lta1mvjq6of8e7d5ffd991eefw9089eg7voch3ehfveecnbwa4ghemm9f5ead3986j98eq7b71def7ff"
                + "jf79ek9rocb6edeqdeoc9yareqae9y6oggdm6l857ia2ehjykdm1cxhjhjhjhjhjhjqjj2h7h7h7h799999999kil6lwlwlwlwlw"
                + "eqlvkokokokohtg1i2f8f8f8f8f8f8krd5d9d9d9d990909090ech3eheheheheherehghghghgheae8eas4m5fqbwhth0d391b4"
                + "bsg7le5v5g6lasacbhe6euatlgzda9a9l0sw");
        put(m, "Modern Love", 0,
                "8c94bxgogohflm4jgogogok6a5fp94ckhzd9f9h5jqg1gkfzcsgj94goetk6etf0ksjbisfnkzehf1flj086dkhifkjuj8i2eogo"
                + "ipfkfrh4e2mcgoi9khgofrgohwpu9kgyfbcbffd2a6eaf29398d1a8uck0cqeqfkccczaxhhb9iweqf6eegockgok68c94goi2em"
                + "gogoffgoapbei4i7fpgohx6xk6ahaj9kiii194go93bei4stt1uof0mkjbmkmkmkjbocfnehehehdn86868686kpj8i2i2i2i2i2"
                + "k6i2h4h4h4h4i9dag2gygygygygygyl2cbd2d2d2d293939393ffk0cqcqcqcqcqk6cqhhhhhhhhf6gof6t3mafkczi9kheef2go"
                + "gokjp96x6xa5bxbxhwb3b394penyaxaxjqzz");
        put(m, "Montserrat", 0,
                "7a78adjch3n1il5m9595aqfz5wam5w9biea1fsfoidfqgxgdhqgx5w5wfzfzfzfrspjxkyjzmyilhllhml8edxjrgdqjmlnbjynb"
                + "k3h3fym0jeuvi8hni38u9b8ug0dwgogeiufniugs9fj2it7h7mgp7hthithfiuiub5dlbaipf2ofeuf2e79a879afz7a78fnhpjg"
                + "jb87dmgomhb5d9fzammhgobnfzbybygoiuhk70gobybid9slslslfrjxjxjxjxjxjxspjzilililil8e8e8e8en3mlnbnbnbnbnb"
                + "fznbm0m0m0m0hnjyikgegegegegegerefngsgsgsgs7h7h7h7hhlithfhfhfhfhffzhfipipipipf2iuf2v5toh3dlhni3e7c6go"
                + "godwrs5w5w5wamamamf7f787hzx28b8bmasd");
        put(m, "Montserrat", 1,
                "7a78adjch3n1il5m9595aqfz5wam5w9biea1fsfoidfqgxgdhqgx5w5wfzfzfzfrspjxkyjzmyilhllhml8edxjrgdqjmlnbjynb"
                + "k3h3fym0jeuvi8hni38v9b8ug0dwgoiuiufniugs9fj2it7h7mge7hthithfiuiub5dlbaipf2ofeuf2e79b879bfz7a78fnhpjg"
                + "jb87dmgomhb5d9g0ammhgobnfzbybygoiuhk70gobybjdaslslslfrjxjxjxjxjxjxspjzilililil8e8e8e8en3mlnbnbnbnbnb"
                + "fznbm0m0m0m0hnjyijiuiuiuiuiuiurffngsgsgsgs7h7h7h7hhlithfhfhfhfhffzhfipipipipf2iuf2v5tph3dlhni3e7c8go"
                + "godwrs5w5w5wamamamf7f787hzx28b8cmbsd");
        put(m, "Montserrat", 2,
                "7v81c5k0hqodk86e9x9yc2gn7aaq7aawivawgeggj5gjhph8ichp7a7agngngngdsrlal9kdmyinhrlfmg94f1kkgsqjmgngkcng"
                + "kfhqh6lwkqwbjuisina8awa8godwgoh5j6gfj8hjarjgj78d8jic8dt5j7i7j6j6bzerc3j3gmq1gjgmf3av8lavgn7v81gfikjg"
                + "kg8leigoltbgfsgnaqltgobmgnbybygoj6j28egobybvfst0t0t0gdlalalalalalau0kdinininin94949494nbmgngngngngng"
                + "gnnglwlwlwlwiskcj9h5h5h5h5h5h5rogfhjhjhjhj8d8d8d8di9j7i7i7i7i7i7gni7j3j3j3j3gmj6gmvktkhqerisinf3ddgo"
                + "godwrs7a7a7adududugfgf9ym6yy9i9imst2");
        put(m, "Neue Haas Grotesk Text Pro", 0,
                "7e7c9thahsowic5a9e9ebbgp60ae60c6ijaygmh1hbgeheffhghe6666f8gpf8f7pzjej7l0kciihalpkn78ezj2gjohklmyhzmx"
                + "jgioi6jkigqtijiuik9wc69wdnbveyfmgifkgkg19qgjfv6566f26bnofvgigkgkajei9rfodplpfgefe89e6y9ec06t7cflgje2"
                + "gz6yhgdwnzegebfsaei7dwamgpayb7dwfsfv6idw7vf8ebn7ospnf7jejejejejejes2l0iiiiiiii78787878kmklmymymymymy"
                + "gpmyjkjkjkjkiuhzf9fmfmfmfmfmfmpifkg1g1g1g165656565glfvgigigigigigogifofofofoefgkefvgqtioeiiuike8b0dw"
                + "dwdwrs5n5n5nadadadgfgf9ijozr8y8yi9o4");
        put(m, "Neue Haas Grotesk Text Pro", 2,
                "6p81cjhriepvk46pa6a6d6gu72a372cyj8c5gyhni6hii5g0hzi67979f1grf1g4qdkgjqktksimhslskr8ag3kggsokkxm7j2m6"
                + "jvj6ikk4j6rrkajij4bvcybvdzcdeyg4h9g1hagkazh1gs7979ge7dpagsh4hchcbeezatgnf9mughfheybs6qbsd36l81g4hfez"
                + "it6qiddwo3f3e7f4a3iadwc6guc4ccdwgrgs75dw9ifhe7p8q8rog4kgkgkgkgkgkgt1ktimimimim8a8a8a8akwkxm7m7m7m7m7"
                + "gtm7k4k4k4k4jij2gug4g4g4g4g4g4pxg1gkgkgkgk79797979h4gsh4h4h4h4h4grh4gngngngnfhhafhvlr6j6ezjij4eycydw"
                + "dwdwrs6c6c6cc7c7c7hghgb0mvzz8d8dj3qt");
        put(m, "Noto Sans", 0,
                "78andyi5fwn0kc929u9ugvfb6y9g7gbxfbfbfbfbfbfbfbfbfbfb8686fbfbfbeeozhri2hkkafgefk8kl9f7lh7ekp7l4lpgtlp"
                + "haf9fgkbgopugafqfw9wbx9wfbbfg4flh3dch3fo9kh3h67676eu76pzh6gth3h3bhdba1h6e4luepe6d2avf0agfb787hfwfwfw"
                + "fwfbe9g4n49xe5fw8yn49gbwfw9q9qg4hbi77g699qage5lylylyc2hrhrhrhrhrhrohhkfgfgfgfg9f9f9f9fkal4lplplplplp"
                + "fblpkbkbkbkbfqgthjflflflflflflo0dcfofofofo76767676gth6gtgtgtgtgtfbgth6h6h6h6e6h3e6psqaf9dbfqfwd2fwgh"
                + "ghdwrs8o8o6ydwdwbke8e8agmcwp8m8mfwlh");
        put(m, "Noto Sans", 1,
                "7879awhyfbm7ip648282fbfb748p749wfbfbfbfbfbfbfbfbfbfb7474fbfbfbbynkfmgogbijead9iuiw907lfmdandjnk1frk1"
                + "fxe1dxitfcnsene5er829w82fbazfefsg3clg3dv8ug3g37676dr76obg3fng3g3b2c098g3czk3dfczcd9qfb9qfb7879fbfbfb"
                + "fbfbdifen49fd9fb8pn4axbwfb9t9tfeg9i7745p9t9fd9kykyljbyfmfmfmfmfmfmmwgbeaeaeaea90909090ijjnk1k1k1k1k1"
                + "fbk1itititite5frg8fsfsfsfsfsfsmscldvdvdvdv76767676fug3fnfnfnfnfnfbfng3g3g3g3czg3cznvo2e1c0e5ercdfbfe"
                + "fedcqo4u4u6r9y9ybud2d2agldva7z7zfbkt");
        put(m, "Noto Sans", 2,
                "78b7f6iifwozku9gaqaqgvfu82a67xd4fbfbfbfbfbfbfbfbfbfb9090fufufuf4oxj6iohpkkfkf9k4l9at97igfpq7mlm4hgm4"
                + "icfbg3l0i2qvijhcg3aud4aufubfgvgshleahlgfarhli98h8hh88hrai9h7hlhlcmdtc2i9ftnsg2ftdkbuf0bufu787yfwfwfw"
                + "fwfbdigvn4anh3fw8yn4a6bwfwajajgvici77x5pajash3ohohohd9j6j6j6j6j6j6qghpfkfkfkfkatatatatkkmlm4m4m4m4m4"
                + "fum4l0l0l0l0hchgjrgsgsgsgsgsgspheagfgfgfgf8h8h8h8hh7i9h7h7h7h7h7fuh7i9i9i9i9fthlftr1r6fbdthcg3dkfwgv"
                + "gvdwrs9b9b7xfnfne9ececagnrzla8a8fwlh");
        put(m, "Open Sans", 0,
                "787fb5hyfwmvka658888fcfw6t8y7ea7fwfwfwfwfwfwfwfwfwfw7e7efwfwfwbxozhli0hjk9fgeck8ki7r7fh2efp3kylngqln"
                + "h6f9fdk8gjpqg1fkfv95a795f2cgg1fgh1d8h1fl9ff8h27171el71puh2gsh1h1bcd99th2dxlmeke0d0ajfbajfw787ffwfwfw"
                + "fwfbecg1n49udtfw8yn4dwbwfw9n9ng1h7i77e6b9nafdtlololobxhlhlhlhlhlhlo9hjfgfgfgfg7r7r7r7rk2kylnlnlnlnln"
                + "fwlnk8k8k8k8fkgzhafgfgfgfgfgfgnud8flflflfl71717171gkh2gsgsgsgsgsfwgsh2h2h2h2e0h1e0pnq6f9d9fkfvd0g1gg"
                + "ggdwrs4q4q6t9q9qb9dye6aglsxe8g8ggelk");
        put(m, "Open Sans", 2,
                "787yd4hyfvp1ku7e9f9ff5fv828y7xbhfvfvfvfvfvfvfvfvfvfv7x82fvfvfvd9oxj6iohpkkfkf9k4l99797igfpq7mlm4hgm4"
                + "icfbg3l0i2qvijhcg397bh97esbfgvgshleahlgfarfpi98h8hh88hrai9h7hlhlcmdtc2i9ftnsg2ftdkayfbayfv787yfvfvfv"
                + "fvfbdigvn4anh3fv8yn4dwbwfvajajgvici77x5pajash3ohohohd9j6j6j6j6j6j6qghpfkfkfkfk97979797kkmlm4m4m4m4m4"
                + "fvm4l0l0l0l0hchgjrgsgsgsgsgsgspheagfgfgfgf8h8h8h8hh7i9h7h7h7h7h7fvh7i9i9i9i9fthlftr1r6fbdthcg3dkfvgv"
                + "gvdwrs616183cdcdeeecemagnrzla8a8fvkt");
        put(m, "Posterama", 0,
                "7n718zioiokgja5b8v8vbfio6bbx6bavioa5fqgngngnguf7gugu6b6bioioioe1ouj5hejhkmfpfakqkb7o7wh9fcnikommhcmm"
                + "hnftfyknj5qvh3grg58wav8we9dwdwerfveifvfz9tfugd7373eh73oegdgtfvfvald89ogaf4mudff4dj9n7a9nio7n71ioioio"
                + "gn7agjdwekbtd7iobxekdwagioa4a4dwgbge6bdw7ebtd7k5m7n0e1j5j5j5j5j5j5rbjhfpfpfpfp7o7o7o7okokommmmmmmmmm"
                + "iommknknknkngrhcg9ererererereromeifzfzfzfz73737373gdgdgwgwgwgwgwiogwgagagagaf4fvf4rurlftd8grg5djgndw"
                + "dwgonc6b6b6baiaiaic8c87ciptz7t7tionz");
        put(m, "Raleway", 0,
                "737084iwh7juij5h7h7g8xbj69bq59gsh0bweheuf6f5grevghga585pe4c6e4d8n9isiij2jvgxgdjwki6wddhvg7odlgkvhakt"
                + "i5gvgykzivt2hki3hh76fs76eze766f5h5f9hagb96h6g76269eq7kpjg7gjh5h59rdf9bgietmle0f2do776f77e7736zfldvex"
                + "is6ie295n4c2fxg6fun4b07eczasau66h8gq597v7kchfxm4mfped1isisisisisisqpj2gxgxgxgx6w6w6w6wk1lgkvkvkvkvkv"
                + "cpkvkzkzkzkzi3grfbf5f5f5f5f5f5pwf9gbgbgbgb62626262gqg7gjgjgjgjgjexgjgigigigif2grf2w1sqgvdfi3hhdoem96"
                + "bafup15g5a698s8n9jbabe9rcptm9s9sm9k7");
        put(m, "Roboto", 0,
                "6w768wh4fmkcha4v9i9obzfr5h7o7cbhfmfmfmfmfmfmfmfmfmfm6q5ve4f9ejd5oyi4hbi3i8fsfdixjt7kfchfezo9jtj4hjj4"
                + "h4gigli0hponhfgpgn7dbf7dbmcj8lf4fmejfoeq9ofmfb6r6ne36rodfcfufmfs9fec93fbdgkvdsd5ds9e6s9eiw6w6sf7g6jt"
                + "el6oh1bmlucfd1fe7olucqaeeva7a78pfrdl796wa7cnd1kclklmd6i4i4i4i4i4i4pzi3fsfsfsfs7k7k7k7kinjtj4j4j4j4j4"
                + "euj4i0i0i0i0gpgfgjf4f4f4f4f4f4nhejeqeqeqeq6w6w6w6wgafcfufufufufufvfrfbfbfbfbd5g0d5qip8giecgpgnds9hd3"
                + "d4i8lp5k5k5j9u9x9lfcfu9dilqm8c8cfmhd");
        put(m, "Roboto", 1,
                "6u6y8nglf5jqgs4p989ebmfa5b7g74b4f5f5f5f5f5f5f5f5f5f56o5udpese3cqo7hlgshjhpfbewidj87cevgweinjj8ijh0ij"
                + "gmg0g3hhh6nwgwg7g575b275b9c68ceof5e4f7ea9df5eu6k6gdo6knnewfdf5fb95dw8tevd2k9ddcrdd946l94ic6u6lerfoj8"
                + "e66hgjbal6c2csex7gl7cda2efa0a08gfad7716pa0c9crjvl1l3crhlhlhlhlhlhlp7hjfbfbfbfb7c7c7c7ci3j8ijijijijij"
                + "eeijhhhhhhhhg7fxg1eoeoeoeoeoeomre4eaeaeaea6o6o6o6oftewfdfdfdfdfdfefaevevevevcrfjcrppohg0dwg7g5dd97cp"
                + "cqhul65e5e5d9o9s9aevfd93i6pt8484f5gv");
        put(m, "Roboto", 2,
                "6x7j8uggfykji94h9q9rcmf56uay82abfyfyfyfyfyfyfyfyfyfy7u7be6fyecduowiohqi6i2fmf9iyjm84fjhof1objmj5hxj5"
                + "hth4h8ibi5obhnh7gv7pbp7pc6cd96evfmehfnf09yfvfk7d78eu7do2flfofmfoa6ea9efke2kee5dze5967096i06x7vg1gij8"
                + "ew6zhgd2luccdwfbayludwauexadad96h6dl8e7hadcqdwjwl3miduioioioioioioq2i6fmfmfmfm84848484ihjmj5j5j5j5j5"
                + "erj4ibibibibh7gxhkevevevevevevnhehf0f0f0f07l7l7l7lfzflfofofofofofufofkfkfkfkdzfqdzqvp3h4eah7gve5a1dq"
                + "dbhil66h6d6wb7bbb5eyg39zklqp8o8efyhj");
        put(m, "Seaford Display", 0,
                "53619hecenigi25e9d9d9ren4z9e4z8genenenenenenenenenen5i5ienenenb7n8ixgghtjnfiepjhk478c6hdeko5jol3fnl3"
                + "gwf2h3j4hbr2hpgvgea38ga3ena9dkcme9c7e9dc8ndde8676ad267m2e8ece9e997at9he8ckk0cfckbeb56yb5en5361cgenen"
                + "en6yf5dknq9ablen9ecydka8en8t8jdkeede4zdk6t9abli9ixjkb7ixixixixixixqyhtfifififi78787878k7jol3l3l3l3l3"
                + "enl3j4j4j4j4gvfwepcmcmcmcmcmcmkxc7dcdcdcdc67676767ehe8ecececececenece8e8e8e8cke9ckqwnbf2atgvgebeegdk"
                + "dkchjj4v4v4v9191918m8majehqi7272engp");
        put(m, "Tenorite", 0,
                "7c8xbpi0ejejgu6j9sa7f0f97acy7aafejejejejejejejejejej8g88f9f9f9d6phh0fzgnhlece7hniy81aqgte2n8iqikfhik"
                + "giejf6hygsoffpeydma995a9eddmcnfkfncxfoe090f7ej6y6xdh6xn5ejetfnfn9ec79uejdml3e2dgc7af6hafel8d8xd6gbgp"
                + "fv6hf7cnkh9we2f9cykhcnbsf99l9kewerhe7acn839xe2fbi4i6d6h0h0h0h0h0h0mugnecececec8181817kigiqikikikikik"
                + "f9ikhyhyhyhyeygpemfkfkfkfkfkfkm9cxe0e0e0e06y6y6y6dexejetetetetetfdetejejejejdgfndgqmofejc7eydmc790cn"
                + "cngvpl7w7w7acxcxc9dcdcbvm3m48k8kgakp");
        put(m, "Trade Gothic Next Cond", 0,
                "556dald2d2h2fc6d7n7na3d26d7r6d6sd2d2d2d2d2d2d2d2d2d26d6dd2d2d29sh4dteddneocabxeef4679xdibci9f7eidhei"
                + "e1cgbyexcuipcoclc57n6t7ncecd9jbgccbbccbj74bdc45f5fbb5fi7c3brccca7ta57nc3aafma9ae9v7n5x7nd2556dd2d2d2"
                + "d25xaa9ji07nc7d27rbt9j8wd28j8j9jcqbo6d9j8j7xc7k4kvk49sdtdtdtdtdtdtjudncacacaca67676767eof7eieieieiei"
                + "d2eiexexexexcldhcbbgbgbgbgbgbghwbbbjbjbjbj5f5f5f5fccc3brbrbrbrbrd2brc3c3c3c3aeccaekyiqcga5clc59vd29j"
                + "9jemm36d6d6dawawawauaucsn7ob7g7gd2g3");
        put(m, "Trade Gothic Next Cond", 2,
                "576gb2d8d8hqg56g8080amd86g856g71d8d8d8d8d8d8d8d8d8d86g6gd8d8d8aehkebewe7fbcychexfr6taze5byj1ftf4e6f4"
                + "emd5ccfhdhjjdcczcy807580ctd1a5bxcvbqcvby7tbycn5x5xc15xiucmcbcvcu8dal89cmavg8axazad805t80d8576gd8d8d8"
                + "d85taza5i182d5d885bza597d89292a5dac96ga5928ed5lhmblhaeebebebebebebkqe7cycycycy6t6t6t6tfbftf4f4f4f4f4"
                + "d8f4fhfhfhfhcze6d3bxbxbxbxbxbxi9bqbybybyby5x5x5x5xcwcmcbcbcbcbcbd8cbcmcmcmcmazcvazlqj5d5alczcyadd8a5"
                + "a5f7my6g6g6gbebdbdbdbdd0ocpe7y7yd8go");
        put(m, "Trade Gothic Next Light", 0,
                "647qcdfsfsm3iq7q9494b9fs7q967q7qfsfsfsfsfsfsfsfsfsfs7q7qfsfsfsb1lth4ieh4ilfkffifk27gc4h3e0n2jzimgrim"
                + "hxfhfajpfdmzfjf7fg947q94fsfgb4ecfje7fjee81e7fd6f66dw6fn7faecfjfd9gcd8ofaccitcgckcc947r94fs647qfsfsfs"
                + "fs7rceb4mp9cejfs96etb4aufsacacb4g1e87qb4ac9fejntosntb1h4h4h4h4h4h4pdh4fkfkfkfk7g7g7g7giljzimimimimim"
                + "fsimjpjpjpjpf7grf0ececececececmxe7eeeeeeee6f6f6f6ff2faecececececfsecfafafafackfjckrdnzfhcdf7fgccfsb4"
                + "b4icrs7q7q7qcocococ8c8dwsquf9999fskb");
        put(m, "Trade Gothic Next Light", 1,
                "647qcpfsfskrig7q8w8wawfs7q8z7q7kfsfsfsfsfsfsfsfsfsfs7q7qfsfsfsaylggfi1h5iuf5egi7jo7pbyggdzn0k1ith7ir"
                + "hif7egjug0m4g8dwf38w7k8wfsf8b4e6f8dgezdv8fecfg6c68dl6cndf8dzf7f09mcn8nf8ciiwcachci8w7r8wfs647qfsfsfs"
                + "fs7rbsb4mb9de7fs8zelb4amfsacacb4fke87qb4ac96e7npoonpaygfgfgfgfgfgfo9h5f5f5f5f57p7p7p7piuk1ititititit"
                + "fsitjujujujudwh7fde6e6e6e6e6e6m6dgdvdvdvdv6c6c6c6cekf8dzdzdzdzdzfsdzf8f8f8f8chf7chq7naf7cndwf3cifsb4"
                + "b4i0qe7q7q7qcncncnc0c0dwsqu29090fsk3");
        put(m, "Ubuntu", 0,
                "6f7obmijfonuii6p9090dcfo6u8b6uaofofofofofofofofofofo6u6ufofofob8qeifhvh8jtfvexiojl7hdwhhefo7k8lmgwlm"
                + "hhesfpj4i8pthjgmfx95ao95fodoageigdcxgdfjaqg2fv7171ei7lnxfygegdgdaqceb6fydylle7dtd3997r99fo6f7ofofofo"
                + "fo7rdoagmmatdvfo8bmmag98fo9z9zagg4hy6uag9zcedvoaoaoab8ififififififpzh8fvfvfvfv7h7h7h7hk6k8lmlmlmlmlm"
                + "folmj4j4j4j4gmgxhbeieieieieieinvcxfjfjfjfj71717171gdfygegegegegefogefyfyfyfydtgddtrlqbescegmfxd3aqag"
                + "agdsro6j6j6jblblbld8d8a0roy38383fol3");
        put(m, "Ubuntu", 2,
                "6o7ycxjffspijl6v9w9wdyfs6u9g6uc5fsfsfsfsfsfsfsfsfsfs6u6ufsfsfscnr2k1ioi0khgufyjike8sepj0fnoxl0lyhwly"
                + "ijg6h2jnk2qciridgyabc5abfsdw7yfdgsdwgsg8bqgigd8181g38snygdgvgsgsbqdhccgdfalsfef7dwab8yabfs6o7yfsfsfs"
                + "fs8eebewm4b3glfs9gm4aga3fsa3a37ygijq6u92a3cpgloeoeoecnk1k1k1k1k1k1rmi0gugugugu8s8s8s8sksl0lylylylyly"
                + "fslyjnjnjnjnidhwibfdfdfdfdfdfdoadwg8g8g8g881818181gpgdgvgvgvgvgvfsgvgdgdgdgdf7gsf7rpq1g6dhidgydwbqb2"
                + "addwrs6r6r6rcmcmcmdqdqa4rszz9393fsnq");
        put(m, "Univers Condensed", 0,
                "66996ycccclmij6y7q7qccdw6699667qcccccccccccccccccccc6666dwdwdwccopgzgzfggzdwccgzgz7qdwfgccn5ijgzfggz"
                + "fgfgdwgzfgopfgdwdw7q7q7qdwdw7qdwdwdwdwdw7qdwdw6666cc66k2dwdwdwdw99cc7qdwcck2ccccat7m6y7mdw6699cccccc"
                + "cc6ycc7qm88cccdw99b47qb4dw85857qdwc8667q858ccckykykyccgzgzgzgzgzgzn5fgdwdwdwdw7q7q7q7qgzijgzgzgzgzgz"
                + "dwgzgzgzgzgzdwfgfgdwdwdwdwdwdwlmdwdwdwdwdw66666666dwdwdwdwdwdwdwdwdwdwdwdwdwccdwccn5lmfgccdwdwatcc7q"
                + "7qdwrs666666cccccccccc99rsop6666ccmx");
        put(m, "Walbaum Display", 0,
                "6i5j92i1ecsikz788p8patgo718w5zaohd9mfseseaeffbdyf7fb5z71gogogobpm4i4hqjmlliwhul8m3a8cpjbiopul3kmgqli"
                + "ioephmlrhqtpi7hiie9xao9xe6dwdwe1fldifidy7qejg07978ey78okg4edfgflbnbd9wfudqm1egefcn9i3d9igo6i5jdifzfi"
                + "j53dbfdwl2bhefgo8wl2dwadgoc4bvdwg3eh5zdw75bcefnwqtqubpi4i4i4i4i4i4t0jmiwiwiwiwa8a8a8a8lll3kmkmkmkmkm"
                + "gokslrlrlrlrhigugbe1e1e1e1e1e1m4didydydydy79797979ebg4edededededgoegfufufufuefflefu4ocepbdhiiecnd5dw"
                + "dwdwrs6s6s6scecechczczdrilzz8m8mirtj");
        return m;
    }
}
