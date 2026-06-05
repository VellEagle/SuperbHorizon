package com.atsuishio.superbhorizon.client.renderer;

import com.atsuishio.superbhorizon.SuperbHorizonConfig;
import com.atsuishio.superbhorizon.network.GhostNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.UUID;

/**
 * クライアント側でレンダリングされる各「ゴースト車両」の位置、姿勢、アニメーション状態の最新状態および
 * 前回の状態をキャッシュし、フレームごとの滑らかな補間位置・角度を計算するデータコンテナクラス。
 *
 * [最適化] renderAlpha() の呼び出しコストを削減するため、レンダーフレームの開始時に
 * 一度だけ alpha 値を計算してキャッシュする prepareFrame(gameTime, partialTick) を追加。
 * 各 renderX/Y/Z/Yaw... メソッドはキャッシュされた cachedAlpha を参照するよう変更。
 */
public class VehicleGhostData {
    public int entityId;                       // サーバー側の実EntityID (-1の場合は現在実Entityがロードされていない)
    public final UUID vehicleId;               // 車両の固有UUID
    public final String typeKey;               // 車両の登録名 (Registry Name、例: "superbwarfare:v22")
    
    // 現在の最新の目標値 (座標)
    public double x, y, z;
    // 前回のTickにおける開始値 (座標)
    public double prevX, prevY, prevZ;
    
    // 現在の最新の目標回転姿勢 (ヨー・ピッチ・ロール)
    public float yaw, pitch, roll;
    // 前回のTickにおける開始回転姿勢 (ヨー・ピッチ・ロール)
    public float prevYaw, prevPitch, prevRoll;
    
    // 最新のアニメーション変数群
    public GhostNetwork.GhostAnimationState animation;
    // 前回のTick時におけるアニメーション変数群
    public GhostNetwork.GhostAnimationState prevAnimation;
    
    // 最後にネットワーク更新パッケージを受け取ったクライアント側のGameTime (GameTicks)
    public long lastUpdateGameTime;

    // [最適化] フレームごとの補間アルファ値キャッシュ。
    // prepareFrame() で一度だけ計算し、同一フレーム内の全補間メソッドで再利用する。
    // Minecraft.getInstance() と割り算の多重呼び出しを排除する。
    private float cachedAlpha = 1.0F;

    // [最適化] ライト値キャッシュ。ゴーストの座標が変わるまで再計算しない。
    // samplePackedLight() は BlockPos 生成 + LightLayer 2回問い合わせのコストが高い。
    private int cachedPackedLight = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
    private double cachedLightX = Double.NaN;
    private double cachedLightY = Double.NaN;
    private double cachedLightZ = Double.NaN;

    /**
     * アニメーション状態なしでゴーストインスタンスを初期作成します。
     */
    public VehicleGhostData(int entityId, UUID vehicleId, String typeKey, double x, double y, double z, float yaw, float pitch, float roll) {
        this(entityId, vehicleId, typeKey, x, y, z, yaw, pitch, roll, GhostNetwork.GhostAnimationState.EMPTY);
    }

    /**
     * アニメーション状態込みでゴーストインスタンスを初期作成します。
     */
    public VehicleGhostData(int entityId, UUID vehicleId, String typeKey, double x, double y, double z, float yaw, float pitch, float roll,
                            GhostNetwork.GhostAnimationState animation) {
        this.entityId = entityId;
        this.vehicleId = vehicleId;
        this.typeKey = typeKey;
        this.x = this.prevX = x;
        this.y = this.prevY = y;
        this.z = this.prevZ = z;
        this.yaw = this.prevYaw = yaw;
        this.pitch = this.prevPitch = pitch;
        this.roll = this.prevRoll = roll;
        this.animation = animation;
        this.prevAnimation = animation;
        this.lastUpdateGameTime = currentGameTime();
    }

    /**
     * サーバーからのパケット受信時に呼び出され、最新の座標・角度を上書きし、前回の値を過去にシフトします。
     */
    public void update(int entityId, double x, double y, double z, float yaw, float pitch, float roll) {
        update(entityId, x, y, z, yaw, pitch, roll, GhostNetwork.GhostAnimationState.EMPTY);
    }

    /**
     * サーバーからのアニメーション込みのパケット受信時に呼び出され、前回の状態を prev フィールドに退避したうえで最新の状態を反映します。
     */
    public void update(int entityId, double x, double y, double z, float yaw, float pitch, float roll,
                       GhostNetwork.GhostAnimationState animation) {
        this.entityId = entityId;
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;
        this.prevRoll = this.roll;
        this.prevAnimation = this.animation;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.animation = animation;
        this.lastUpdateGameTime = currentGameTime();
        // 位置が更新されたのでライトキャッシュを無効化
        this.cachedLightX = Double.NaN;
    }

    /**
     * ゴーストがアニメーションデータを保持しているか確認します。
     */
    public boolean hasAnimationState() {
        return animation != null && animation != GhostNetwork.GhostAnimationState.EMPTY;
    }

    /**
     * [最適化] レンダーフレームの開始時に一度だけ呼び出し、補間アルファ値をキャッシュします。
     * これにより、同一フレーム内で renderX/Y/Z/Yaw... が呼ばれるたびに
     * Minecraft.getInstance() を叩いたり除算を繰り返すコストを排除します。
     *
     * @param gameTime   mc.level.getGameTime() の値（呼び出し元で一度だけ取得したもの）
     * @param partialTick 現在のフレーム補間端数
     */
    public void prepareFrame(long gameTime, float partialTick) {
        float elapsed = (gameTime - lastUpdateGameTime) + partialTick;
        this.cachedAlpha = Mth.clamp(elapsed / SuperbHorizonConfig.TICK_INTERVAL.get().floatValue(), 0.0F, 1.0F);
    }

    /**
     * [最適化] ライト値をキャッシュして返します。
     * 位置が変わっていない限り BlockPos 生成と LightLayer 問い合わせをスキップします。
     */
    public int getCachedPackedLight(net.minecraft.world.level.Level level) {
        if (Double.isNaN(cachedLightX) || cachedLightX != x || cachedLightY != y || cachedLightZ != z) {
            cachedLightX = x;
            cachedLightY = y;
            cachedLightZ = z;
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(x, y, z);
            if (level.isLoaded(pos)) {
                int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
                int sky   = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
                cachedPackedLight = net.minecraft.client.renderer.LightTexture.pack(block, sky);
            } else {
                int sky = Math.max(0, 15 - level.getSkyDarken());
                cachedPackedLight = net.minecraft.client.renderer.LightTexture.pack(0, sky);
            }
        }
        return cachedPackedLight;
    }

    // --- 各座標・姿勢の描画用補間メソッド群 (レンダーフレームごとに呼び出されます) ---
    // [最適化] cachedAlpha を使用。partialTick 引数は DummyEntity 用に残すが、
    //          内部では prepareFrame() でキャッシュされた値を使う。

    public double renderX(float partialTick) {
        return Mth.lerp(resolveAlpha(partialTick), prevX, x);
    }

    public double renderY(float partialTick) {
        return Mth.lerp(resolveAlpha(partialTick), prevY, y);
    }

    public double renderZ(float partialTick) {
        return Mth.lerp(resolveAlpha(partialTick), prevZ, z);
    }

    public float renderYaw(float partialTick) {
        return Mth.rotLerp(resolveAlpha(partialTick), prevYaw, yaw);
    }

    public float renderPitch(float partialTick) {
        return Mth.rotLerp(resolveAlpha(partialTick), prevPitch, pitch);
    }

    public float renderRoll(float partialTick) {
        return Mth.rotLerp(resolveAlpha(partialTick), prevRoll, roll);
    }

    // --- 各アニメーション変数群の描画用補間メソッド群 ---

    public float animAbsoluteSpeed(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.absoluteSpeed, animation.absoluteSpeed);
    }

    public float animTargetSpeed(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.targetSpeed, animation.targetSpeed);
    }

    public float animPower(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.power, animation.power);
    }

    public float animTurretYaw(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.turretYaw, animation.turretYaw);
    }

    public float animTurretPitch(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.turretPitch, animation.turretPitch);
    }

    public float animGunYaw(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.gunYaw, animation.gunYaw);
    }

    public float animGunPitch(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.gunPitch, animation.gunPitch);
    }

    public float animLeftWheelRot(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.leftWheelRot, animation.leftWheelRot);
    }

    public float animRightWheelRot(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.rightWheelRot, animation.rightWheelRot);
    }

    public float animLeftTrack(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.leftTrack, animation.leftTrack);
    }

    public float animRightTrack(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.rightTrack, animation.rightTrack);
    }

    public float animPropellerRot(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.propellerRot, animation.propellerRot);
    }

    public float animGearRot(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.gearRot, animation.gearRot);
    }

    public float animPlaneBreak(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.planeBreak, animation.planeBreak);
    }

    public float animCannonRecoilForce(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.cannonRecoilForce, animation.cannonRecoilForce);
    }

    public int animCannonRecoilTime() {
        return animation.cannonRecoilTime;
    }

    /**
     * ゴーストの有効期限切れチェック。
     * SuperbHorizonConfig.STALE_TICKS が 0 の場合はタイムアウトを無効化し、常にアクティブとして扱います。
     * 0 より大きい場合は、最後の更新から指定Tick以上が経過したゴーストを「失効済み」と判定します。
     */
    public boolean isStale() {
        int staleTicks = com.atsuishio.superbhorizon.SuperbHorizonConfig.STALE_TICKS.get();
        if (staleTicks <= 0) return false;
        long now = currentGameTime();
        return (now - lastUpdateGameTime) > staleTicks;
    }

    /**
     * 車両のテクスチャリソースのパスを構築して返却します。
     * typeKey のnamespace（例: "superbwarfare"）をそのままテクスチャのnamespaceとして使用します。
     */
    public ResourceLocation textureLocation() {
        String namespace = namespaceFromTypeKey();
        String path = pathFromTypeKey();
        return ResourceLocation.fromNamespaceAndPath(namespace, "textures/entity/" + path + ".png");
    }

    /**
     * 車両のPolyMesh用ジオメトリモデル定義ファイル（geo.json）のパスを構築して返却します。
     */
    public ResourceLocation polyMeshLocation() {
        String path = pathFromTypeKey();
        return ResourceLocation.fromNamespaceAndPath(namespaceFromTypeKey(), "custom_geo/" + path + ".geo.json");
    }

    /**
     * [最適化] renderX/Y/Z 等から呼ばれる内部ヘルパー。
     * partialTick が 0.0F または 1.0F（DummyEntity セット用の境界値）の場合はそのまま使用し、
     * それ以外は prepareFrame() でキャッシュされた cachedAlpha を返す。
     * これにより DummyEntity セット処理の正確性を維持しつつ、通常フレームの計算コストを削減する。
     */
    private float resolveAlpha(float partialTick) {
        if (partialTick == 0.0F) return 0.0F;
        if (partialTick == 1.0F) return 1.0F;
        return cachedAlpha;
    }

    private long currentGameTime() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.getGameTime() : 0L;
    }

    private float lerpAnimation(float partialTick, float prev, float current) {
        return Mth.lerp(resolveAlpha(partialTick), prev, current);
    }

    private float rotLerpAnimation(float partialTick, float prev, float current) {
        return Mth.rotLerp(resolveAlpha(partialTick), prev, current);
    }

    private String namespaceFromTypeKey() {
        return typeKey.contains(":") ? typeKey.substring(0, typeKey.indexOf(':')) : "minecraft";
    }

    private String pathFromTypeKey() {
        return typeKey.contains(":") ? typeKey.substring(typeKey.indexOf(':') + 1) : typeKey;
    }
}
