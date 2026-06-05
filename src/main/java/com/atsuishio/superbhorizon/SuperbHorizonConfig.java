package com.atsuishio.superbhorizon;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * SuperbHorizon Mod の共通設定 (Common Configuration) を定義するクラス。
 * ネットワークの同期頻度、位置・回転のしきい値、描画距離の切り替え閾値などを管理します。
 */
public class SuperbHorizonConfig {
    public static final ForgeConfigSpec SPEC;

    // --- 同期に関する設定項目 (Sync Config) ---
    public static final ForgeConfigSpec.IntValue TICK_INTERVAL;
    public static final ForgeConfigSpec.IntValue SAVE_INTERVAL;
    public static final ForgeConfigSpec.DoubleValue POSITION_EPSILON;
    public static final ForgeConfigSpec.DoubleValue ROTATION_EPSILON;
    public static final ForgeConfigSpec.DoubleValue MAX_SYNC_DISTANCE;

    // --- 描画に関する設定項目 (Render Config) ---
    public static final ForgeConfigSpec.DoubleValue GHOST_SWITCH_DISTANCE;
    public static final ForgeConfigSpec.IntValue STALE_TICKS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_POLYMESH;
    public static final ForgeConfigSpec.BooleanValue PREFER_ANIMATED_ENTITY_FALLBACK;

    // --- LOD (Level of Detail) に関する設定項目 ---

    /**
     * フルモデル描画（アニメーション・テクスチャあり）を行う最大距離。
     * この距離より遠いゴーストはアニメーションなしの静的モデルに切り替わります。
     * GHOST_SWITCH_DISTANCE より大きい値にする必要があります。
     * 0 に設定すると LOD を無効化し、すべての距離でフルモデルを描画します。
     */
    public static final ForgeConfigSpec.DoubleValue LOD_FULL_DISTANCE;

    /**
     * 静的モデルを描画する最大距離（メートル）。
     * この距離より遠いゴーストは描画を完全にスキップします。
     * LOD_FULL_DISTANCE より大きい値にする必要があります。
     * 0 に設定するとこのカリングを無効化します。
     */
    public static final ForgeConfigSpec.DoubleValue LOD_STATIC_DISTANCE;

    /**
     * 静的LOD（アニメなし）のゴーストを N フレームに 1 回だけ描画するフレームスキップ数。
     * 1 でスキップなし、2 で 1 フレームおきに描画（実効 30fps@60fps）、
     * 4 で 3 フレームおきに描画（実効 15fps@60fps）。
     * 遠距離の静的ゴーストはほとんど動きが見えないため、4〜6 程度でも違和感がありません。
     */
    public static final ForgeConfigSpec.IntValue STATIC_FRAME_SKIP;

    /**
     * 一度に描画するゴーストの最大台数。
     * 大量の車両が視界に入っているときの描画負荷を上限で制御します。
     * 0 で無制限。
     */
    public static final ForgeConfigSpec.IntValue MAX_GHOST_COUNT;


    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("sync");
        TICK_INTERVAL = builder
                .comment("ゴースト車両のネットワーク同期を実行する間隔（サーバーTick単位）。数値が小さいほど同期が高頻度になります。")
                .defineInRange("tickInterval", 2, 1, 20);
        SAVE_INTERVAL = builder
                .comment("移動中のゴースト車両データをワールドに書き出す保存フラグを設定する間隔（サーバーTick単位）。")
                .defineInRange("saveInterval", 20, 1, 200);
        POSITION_EPSILON = builder
                .comment("位置変更パケットを送信するために必要な最小の位置変化量（メートル）。")
                .defineInRange("positionEpsilon", 0.01D, 0.0D, 1.0D);
        ROTATION_EPSILON = builder
                .comment("回転変更パケットを送信するために必要な最小の回転変化量（度）。")
                .defineInRange("rotationEpsilon", 0.25D, 0.0D, 10.0D);
        MAX_SYNC_DISTANCE = builder
                .comment("ゴースト同期を行うプレイヤーからの最大同期距離。0に設定すると距離フィルタリングを無効化します。")
                .defineInRange("maxSyncDistance", 5000.0D, 0.0D, 32000.0D);
        builder.pop();

        builder.push("render");
        GHOST_SWITCH_DISTANCE = builder
                .comment("ゴーストレンダラーと通常のエンティティレンダラーが切り替わる基準距離。これより遠い車両はゴーストとして描画されます。")
                .defineInRange("ghostSwitchDistance", 96.0D, 16.0D, 1024.0D);
        STALE_TICKS = builder
                .comment("指定されたTickの間アップデートが来ない場合にゴーストを非表示にする時間。0でタイムアウト非表示を無効化します。")
                .defineInRange("staleTicks", 200, 0, 1200);
        ENABLE_POLYMESH = builder
                .comment("カスタムPolyMeshレンダラーがロードされている場合、反射描画を有効にするか。")
                .define("enablePolyMesh", true);
        PREFER_ANIMATED_ENTITY_FALLBACK = builder
                .comment("アニメーションデータが利用可能な場合、遠距離のゴースト描画にSuperb Warfareのアニメーション対応エンティティレンダラーを優先使用するか。")
                .define("preferAnimatedEntityFallback", true);
        builder.pop();

        builder.push("lod");
        builder.comment(
                "--- LOD (Level of Detail) 設定 ---\n" +
                "距離に応じてゴーストの描画精度・頻度を段階的に落とし、クライアントの描画負荷を削減します。\n" +
                "  ・GHOST_SWITCH_DISTANCE 以内    → 通常エンティティレンダラー（ゴーストOFF）\n" +
                "  ・LOD_FULL_DISTANCE 以内        → フルモデル（アニメーション・テクスチャあり、毎フレーム描画）\n" +
                "  ・LOD_STATIC_DISTANCE 以内      → 静的モデル（バインドポーズ固定・アニメなし、フレームスキップあり）\n" +
                "  ・LOD_STATIC_DISTANCE 超        → 描画スキップ（完全カリング）");
        LOD_FULL_DISTANCE = builder
                .comment("フルアニメーションモデルを描画する最大距離（メートル）。これより遠いゴーストはアニメーションなしの静止モデルになります。0でLOD段階を無効化します。")
                .defineInRange("lodFullDistance", 5000.0D, 0.0D, 32000.0D);
        LOD_STATIC_DISTANCE = builder
                .comment("静的モデルを描画する最大距離（メートル）。これより遠いゴーストは描画を完全にスキップします。0で無制限（スキップなし）。推奨: LOD_FULL_DISTANCE の 2〜3 倍程度。")
                .defineInRange("lodStaticDistance", 5000.0D, 0.0D, 32000.0D);
        STATIC_FRAME_SKIP = builder
                .comment("静的LODゴーストを N フレームに 1 回だけ描画するフレームスキップ数。1=毎フレーム（デフォルト・推奨）。2以上にすると点滅が発生するため、現状は1のまま使用してください。")
                .defineInRange("staticFrameSkip", 1, 1, 16);
        MAX_GHOST_COUNT = builder
                .comment("一度に描画するゴーストの最大台数。視界内にゴーストが多すぎるときの上限制御。0で無制限。")
                .defineInRange("maxGhostCount", 20, 0, 512);

        builder.pop();

        SPEC = builder.build();
    }

    private SuperbHorizonConfig() {
    }
}
