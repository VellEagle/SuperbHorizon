package com.atsuishio.superbhorizon;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * SuperbHorizon Mod の共通設定 (Common Configuration) を定義するクラス。
 * ネットワークの同期頻度、位置・回転のしきい値、描画距離の切り替え閾値などを管理します。
 */
public class SuperbHorizonConfig {
    public static final ForgeConfigSpec SPEC;

    // --- 同期に関する設定項目 (Sync Config) ---
    
    // パケットをサーバーからクライアントに送信する時間間隔（サーバーTick数）
    public static final ForgeConfigSpec.IntValue TICK_INTERVAL;
    
    // 動いているゴーストデータをチャンク保存用にマークする時間間隔（サーバーTick数）
    public static final ForgeConfigSpec.IntValue SAVE_INTERVAL;
    
    // 位置パケットを送信する最小の座標変化量（メートル）
    public static final ForgeConfigSpec.DoubleValue POSITION_EPSILON;
    
    // 回転角度パケットを送信する最小の角度変化量（度数）
    public static final ForgeConfigSpec.DoubleValue ROTATION_EPSILON;
    
    // ゴーストパケットを同期するプレイヤーからの最大半径距離（この距離より遠いと同期しない）
    public static final ForgeConfigSpec.DoubleValue MAX_SYNC_DISTANCE;

    // --- 描画に関する設定項目 (Render Config) ---
    
    // ゴースト描画（VehicleGhostRenderer）と通常エンティティ描画が切り替わる境界距離
    public static final ForgeConfigSpec.DoubleValue GHOST_SWITCH_DISTANCE;
    
    // クライアント側でパケットが途絶えた際、何Tickでゴーストを非表示にするかの生存時間
    public static final ForgeConfigSpec.IntValue STALE_TICKS;
    
    // PolyMeshリフレクションレンダラーを有効にするかどうか
    public static final ForgeConfigSpec.BooleanValue ENABLE_POLYMESH;
    
    // アニメーションデータが存在する場合、ゴースト描画にSuperb Warfare本来のエンティティレンダラーを優先するか
    public static final ForgeConfigSpec.BooleanValue PREFER_ANIMATED_ENTITY_FALLBACK;

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
                .defineInRange("maxSyncDistance", 4096.0D, 0.0D, 32000.0D);
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

        SPEC = builder.build();
    }

    private SuperbHorizonConfig() {
    }
}
