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
    }

    /**
     * ゴーストがアニメーションデータを保持しているか確認します。
     */
    public boolean hasAnimationState() {
        return animation != null && animation != GhostNetwork.GhostAnimationState.EMPTY;
    }

    // --- 各座標・姿勢の描画用補間メソッド群 (レンダーフレームごとに呼び出されます) ---

    public double renderX(float partialTick) {
        return Mth.lerp(renderAlpha(partialTick), prevX, x);
    }

    public double renderY(float partialTick) {
        return Mth.lerp(renderAlpha(partialTick), prevY, y);
    }

    public double renderZ(float partialTick) {
        return Mth.lerp(renderAlpha(partialTick), prevZ, z);
    }

    public float renderYaw(float partialTick) {
        return Mth.rotLerp(renderAlpha(partialTick), prevYaw, yaw);
    }

    public float renderPitch(float partialTick) {
        return Mth.rotLerp(renderAlpha(partialTick), prevPitch, pitch);
    }

    public float renderRoll(float partialTick) {
        return Mth.rotLerp(renderAlpha(partialTick), prevRoll, roll);
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
     * 動いていない、もしくは遠くのチャンクがアンロードされた状態でもゴーストモデルを確実に維持させるため、
     * 期限切れによる削除を無効化（常にfalseを返却）しています。
     */
    public boolean isStale() {
        return false;
    }

    /**
     * 車両のテクスチャリソースのパスを構築して返却します。
     */
    public ResourceLocation textureLocation() {
        String path = pathFromTypeKey();
        return ResourceLocation.fromNamespaceAndPath("superb_modern_combat", "textures/entity/" + path + ".png");
    }

    /**
     * 車両のPolyMesh用ジオメトリモデル定義ファイル（geo.json）のパスを構築して返却します。
     */
    public ResourceLocation polyMeshLocation() {
        String path = pathFromTypeKey();
        return ResourceLocation.fromNamespaceAndPath(namespaceFromTypeKey(), "custom_geo/" + path + ".geo.json");
    }

    /**
     * パケットを受け取ってからの経過時間をもとに、補間に使用するアルファブレンド比率（0.0F〜1.0F）を算出します。
     */
    private float renderAlpha(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 1.0F;

        // 前回のパケット受信時点からの経過Tick数 ＋ partialTick (現在のフレーム補間端数)
        float elapsed = (mc.level.getGameTime() - lastUpdateGameTime) + partialTick;
        
        // 設定されたTICK_INTERVALを基準に割り算し、0.0〜1.0の範囲に収めて滑らかに遷移させます
        return Mth.clamp(elapsed / SuperbHorizonConfig.TICK_INTERVAL.get().floatValue(), 0.0F, 1.0F);
    }

    private long currentGameTime() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.getGameTime() : 0L;
    }

    private float lerpAnimation(float partialTick, float prev, float current) {
        return Mth.lerp(renderAlpha(partialTick), prev, current);
    }

    private float rotLerpAnimation(float partialTick, float prev, float current) {
        return Mth.rotLerp(renderAlpha(partialTick), prev, current);
    }

    private String namespaceFromTypeKey() {
        return typeKey.contains(":") ? typeKey.substring(0, typeKey.indexOf(':')) : "minecraft";
    }

    private String pathFromTypeKey() {
        return typeKey.contains(":") ? typeKey.substring(typeKey.indexOf(':') + 1) : typeKey;
    }
}
