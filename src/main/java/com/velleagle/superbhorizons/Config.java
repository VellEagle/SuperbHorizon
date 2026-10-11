package com.velleagle.superbhorizons;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Arrays;
import java.util.List;

/**
 * 設定。生成されるファイルは config/superbhorizons-common.toml です。
 * サーバーとクライアントで別々のファイルになります
 * (サーバー側の値はサーバーの config フォルダを編集してください)。
 *
 * ※ 設定ファイルに書き出される説明文 (comment) はすべて英語です。
 *   各項目の日本語の説明は、このファイルの各フィールドのコメントを見てください。
 */
public class Config {

    public static final ModConfigSpec SPEC;

    // =====================================================================
    // [sync] サーバー側の設定 (車両情報の送信)
    // =====================================================================

    /** 何 tick ごとに車両情報を送るか。4 = 毎秒 5 回。小さいほど滑らかで通信量が増えます。 */
    public static final ModConfigSpec.IntValue UPDATE_INTERVAL;

    /** 1 人のプレイヤーに送る車両の最大数。近い順に打ち切られます。 */
    public static final ModConfigSpec.IntValue MAX_VEHICLES;

    /** この距離を超えた車両はゴースト表示しません (ブロック単位)。既定 4096。 */
    public static final ModConfigSpec.DoubleValue MAX_DISTANCE;

    /**
     * この距離より近い車両はゴースト表示しません (ブロック単位)。
     * 近距離では本物のエンティティが描画されるため、二重描画を防ぐための下限です。
     */
    public static final ModConfigSpec.DoubleValue MIN_DISTANCE;

    /** ゴースト表示の対象とみなすクラス名の接頭辞 (アドオン車両にも対応するため親クラスもさかのぼります)。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> VEHICLE_CLASS_PREFIXES;

    /** 可動部分 (砲塔・車輪・プロペラ等) の状態も送るか。 */
    public static final ModConfigSpec.BooleanValue SEND_ANIMATION;

    /** この距離を超えた車両には可動部分の状態を送りません (ブロック単位)。既定 4096。 */
    public static final ModConfigSpec.DoubleValue ANIMATION_MAX_DISTANCE;

    /** 射撃 (どの武器を撃ったか) も送るか。ゴーストの射撃アニメーション・砲口の閃光に使います。 */
    public static final ModConfigSpec.BooleanValue SEND_FIRE_EVENTS;

    /** 武器データ (弾数・選択中の弾種など) も送るか。弾が補充されると見える武装 (翼下のミサイル等) に使います。 */
    public static final ModConfigSpec.BooleanValue SEND_AMMO;

    /** 砲口の煙・炎を「大」で出す弾のクラス名 (親クラスも含めて完全一致)。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MUZZLE_BIG_CLASSES;

    /** 砲口の煙・炎を「中」で出す弾のクラス名 (親クラスも含めて完全一致)。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MUZZLE_MEDIUM_CLASSES;

    /** 発射体のゴーストに弾道の煙 (砲弾の煙の筋・ロケットの炎の筋) を出すか。既定 false。 */
    public static final ModConfigSpec.BooleanValue GHOST_TRAILS;

    /** 弾の種類ごとの弾道の煙 (「クラス名=種類」または「クラス名=種類:最低速度」)。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRAIL_CLASSES;

    /** 砲弾・ロケット・ミサイル・爆弾などの発射体もゴースト表示するか (試験的な機能)。既定 false。 */
    public static final ModConfigSpec.BooleanValue GHOST_PROJECTILES;

    /** 1 プレイヤーあたりに送る発射体の最大数。近い順に打ち切られます。 */
    public static final ModConfigSpec.IntValue MAX_PROJECTILES;

    /** ゴースト対象とみなす発射体のクラス名 (親クラスも含めて前方一致で判定)。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PROJECTILE_CLASS_PREFIXES;

    /** 車両から撃たれた場合だけゴースト表示する弾 (機銃弾など) のクラス名 (前方一致)。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> VEHICLE_BULLET_CLASS_PREFIXES;

    // =====================================================================
    // [client] クライアント側の設定 (描画)
    // =====================================================================

    /** クライアント側でゴースト描画を行うか。 */
    public static final ModConfigSpec.BooleanValue RENDER_GHOSTS;

    /**
     * 遠方の車両の表示方法。
     *   dhModel : DH のバッファへモデルを直接描く (推奨。形もアニメーションも保たれ、LOD 地形の裏に隠れる)
     *   model   : こちらでモデルを描画する (非常用。LOD 地形を貫通して見える)
     * DH が入っていない場合は自動的に model になります。
     */
    public static final ModConfigSpec.ConfigValue<String> GHOST_STYLE;

    /**
     * ゴーストを常に通常の (細かい) モデルで描くか。
     * SBW の簡略モデル (遠距離用) では射撃アニメーションや武装の表示が省略されるため、既定 true。
     */
    public static final ModConfigSpec.BooleanValue GHOST_FULL_DETAIL;

    /** DH から受け取る行列の並び順。true = 行優先 (動作確認済み)。モデルが変形して見える場合だけ反転させます。 */
    public static final ModConfigSpec.BooleanValue DH_MATRIX_ROW_MAJOR;

    /** DH の深度 (奥行き) の向き。auto (推奨) / normal / reversed。自動判定が外れたときの逃げ道です。 */
    public static final ModConfigSpec.ConfigValue<String> DH_DEPTH_DIRECTION;

    /** シェーダー使用時に、ゴーストを LOD 地形の裏へ隠す処理を行うか。画面がおかしい場合は false。 */
    public static final ModConfigSpec.BooleanValue DH_SHADER_OCCLUSION;

    /** デバッグログを出力するか。既定 false。 */
    public static final ModConfigSpec.BooleanValue DEBUG_LOG;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("sync");
        UPDATE_INTERVAL = b
                .comment("How often vehicle data is sent to players, in ticks. 4 = 5 times per second.",
                        "Lower values are smoother but use more bandwidth.")
                .defineInRange("updateIntervalTicks", 4, 1, 100);
        MAX_VEHICLES = b
                .comment("Maximum number of vehicles sent to each player. The nearest ones are kept.")
                .defineInRange("maxVehiclesPerPlayer", 256, 1, 4096);
        MAX_DISTANCE = b
                .comment("Maximum distance (in blocks) at which ghost vehicles are shown.")
                .defineInRange("maxGhostDistance", 4096.0D, 64.0D, 100000.0D);
        MIN_DISTANCE = b
                .comment("Minimum distance (in blocks) at which ghost vehicles are shown.",
                        "Closer vehicles are rendered as real entities, so this prevents double rendering.")
                .defineInRange("minGhostDistance", 96.0D, 0.0D, 1024.0D);
        SEND_ANIMATION = b
                .comment("Also send the state of moving parts (turrets, wheels, tracks, propellers, etc.).")
                .define("sendAnimation", true);
        ANIMATION_MAX_DISTANCE = b
                .comment("Moving-part states are not sent for vehicles farther than this distance (in blocks).",
                        "Lower it to save bandwidth.")
                .defineInRange("animationMaxDistance", 4096.0D, 0.0D, 100000.0D);
        SEND_FIRE_EVENTS = b
                .comment("Also send firing events, so distant ghosts play firing animations (including muzzle flashes).")
                .define("sendFireEvents", true);
        SEND_AMMO = b
                .comment("Also send weapon data (ammo count, selected ammo type, etc.).",
                        "Weapons that appear when loaded (missiles or bombs under the wings, etc.) are shown on ghosts too.",
                        "Sent only when it changes, at most once per second.")
                .define("sendAmmo", true);
        MUZZLE_BIG_CLASSES = b
                .comment("Projectile classes that create LARGE muzzle smoke/flash on ghosts when fired",
                        "(exact class name match, parent classes are also checked).",
                        "Default: Superb Warfare tank / howitzer shells.")
                .defineList("muzzleEffectBigClasses",
                        Arrays.asList("com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity"),
                        () -> "", o -> o instanceof String);
        MUZZLE_MEDIUM_CLASSES = b
                .comment("Projectile classes that create MEDIUM muzzle smoke/flash on ghosts when fired",
                        "(exact class name match, parent classes are also checked).",
                        "Default: Superb Warfare mortar shells, medium rockets and wire-guided missiles.")
                .defineList("muzzleEffectMediumClasses",
                        Arrays.asList(
                                "com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity",
                                "com.atsuishio.superbwarfare.entity.projectile.MediumRocketEntity",
                                "com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity"),
                        () -> "", o -> o instanceof String);
        GHOST_TRAILS = b
                .comment("Show trails (shell smoke, rocket flames) behind projectile ghosts.",
                        "Only used when ghostProjectiles is enabled.")
                .define("ghostTrails", false);
        TRAIL_CLASSES = b
                .comment("Trail type for each projectile class: \"ClassName=type\" or \"ClassName=type:minSpeed\" (blocks/tick).",
                        "Types: shell (white smoke) / large / medium / small (flames) / none",
                        "Only the last part of the class name is matched, and parent classes are also checked",
                        "(so it works for addon projectiles too).",
                        "Defaults match Superb Warfare. Large missiles are not included because Superb Warfare",
                        "already renders their smoke at long range.")
                .defineList("trailClasses",
                        Arrays.asList(
                                "CannonShellEntity=shell",
                                "GunGrenadeEntity=shell",
                                "MortarShellEntity=medium:5",
                                "MediumRocketEntity=large",
                                "PtkmProjectileEntity=large",
                                "SmallRocketEntity=small",
                                "IglaMissileEntity=medium",
                                "JavelinMissileEntity=medium",
                                "RpgRocketStandardEntity=medium",
                                "RpgRocketTBGEntity=medium",
                                "Ru9m336MissileEntity=medium",
                                "WireGuideMissileEntity=medium"),
                        () -> "", o -> o instanceof String);
        GHOST_PROJECTILES = b
                .comment("Also show ghosts for projectiles such as shells, rockets, missiles and bombs (experimental).",
                        "Even when disabled, firing animations, muzzle smoke/flashes and under-wing weapons are still synced.")
                .define("ghostProjectiles", false);
        MAX_PROJECTILES = b
                .comment("Maximum number of projectiles sent to each player. The nearest ones are kept.")
                .defineInRange("maxProjectilesPerPlayer", 64, 0, 1024);
        PROJECTILE_CLASS_PREFIXES = b
                .comment("Projectile class name prefixes that get ghosts (parent classes are also checked).",
                        "Default: the common parent class of Superb Warfare shells, rockets, missiles, bombs and grenades.",
                        "Rifle bullets, mines, C4, etc. are not included.")
                .defineList("projectileClassPrefixes",
                        Arrays.asList("com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile"),
                        () -> "", o -> o instanceof String);
        VEHICLE_BULLET_CLASS_PREFIXES = b
                .comment("Bullet class name prefixes that get ghosts only when fired from a vehicle (e.g. vehicle machine guns).",
                        "Parent classes are also checked. Default: Superb Warfare bullets.",
                        "Bullets fired by infantry are excluded because there are too many of them.",
                        "Tracer colors (red, green, etc.) match the real bullets.")
                .defineList("vehicleBulletClassPrefixes",
                        Arrays.asList("com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity"),
                        () -> "", o -> o instanceof String);
        VEHICLE_CLASS_PREFIXES = b
                .comment("Entity class name prefixes treated as vehicles (parent classes are also checked,",
                        "so addon vehicles based on Superb Warfare vehicles are included).")
                .defineList("vehicleClassPrefixes",
                        Arrays.asList("com.atsuishio.superbwarfare.entity.vehicle"),
                        () -> "", o -> o instanceof String);
        b.pop();

        b.push("client");
        RENDER_GHOSTS = b
                .comment("Render ghost vehicles.")
                .define("renderGhosts", true);
        GHOST_STYLE = b
                .comment("How distant vehicles are rendered.",
                        "  dhModel : Draw the model directly into Distant Horizons' buffer (recommended).",
                        "            Shape and animations are kept, and ghosts are hidden behind LOD terrain.",
                        "  model   : Draw the model in the normal pass (fallback). Ghosts are visible through LOD terrain.",
                        "If Distant Horizons is not installed, model is used automatically.")
                .define("ghostStyle", "dhModel");
        GHOST_FULL_DETAIL = b
                .comment("Always draw ghosts with the full-detail model.",
                        "Superb Warfare's low-detail (long-range) model skips firing animations and weapon display,",
                        "so keep this true if you want to see them on ghosts.",
                        "Setting it to false uses Superb Warfare's low-detail model, which is slightly lighter.")
                .define("ghostFullDetailModel", true);
        DH_MATRIX_ROW_MAJOR = b
                .comment("Element order of the matrix received from Distant Horizons. true = row-major.",
                        "Only flip this if ghost models appear in the wrong place or get distorted as the camera moves.")
                .define("dhMatrixRowMajor", true);
        DH_DEPTH_DIRECTION = b
                .comment("Depth direction used by Distant Horizons: auto / normal / reversed",
                        "  auto     : Detect automatically (recommended)",
                        "  normal   : Nearer = smaller value",
                        "  reversed : Nearer = larger value",
                        "If ghosts show through LOD terrain and disappear in open areas, auto-detection failed.",
                        "Try normal and reversed manually.")
                .define("dhDepthDirection", "auto");
        DH_SHADER_OCCLUSION = b
                .comment("When a shader pack is used, hide ghosts behind Distant Horizons LOD terrain.",
                        "Set to false if the screen looks broken (ghosts will then show through LOD terrain).")
                .define("dhShaderOcclusion", true);
        DEBUG_LOG = b
                .comment("Write debug information to the log.")
                .define("debugLog", false);
        b.pop();

        SPEC = b.build();
    }
}
