package com.velleagle.superbhorizons.server;

import com.velleagle.superbhorizons.network.GhostAnimation;
import com.velleagle.superbhorizons.util.Reflect;
import net.minecraft.world.entity.Entity;

/**
 * サーバー側で、Superb Warfare の車両から可動部分の状態を読み取ります。
 *
 * メソッド名は Superb Warfare の VehicleEntity に合わせています。
 * 一部のメソッドが存在しない車種でも、その項目が 0 になるだけで問題ありません。
 */
public class VehicleAnimationReader {

    public static GhostAnimation read(Entity vehicle) {
        GhostAnimation a = new GhostAnimation();

        a.absoluteSpeed = Reflect.getFloat(vehicle, "getAbsoluteSpeed", 0.0F);
        a.targetSpeed = Reflect.getFloat(vehicle, "getTargetSpeed", 0.0F);
        a.power = Reflect.getFloat(vehicle, "getPower", 0.0F);
        a.planeBreak = Reflect.getFloat(vehicle, "getPlaneBreak", 0.0F);
        a.cannonRecoilForce = Reflect.getFloat(vehicle, "getCannonRecoilForce", 0.0F);

        a.turretYaw = Reflect.getFloat(vehicle, "getTurretYRot", 0.0F);
        a.turretPitch = Reflect.getFloat(vehicle, "getTurretXRot", 0.0F);
        a.gunYaw = Reflect.getFloat(vehicle, "getGunYRot", 0.0F);
        a.gunPitch = Reflect.getFloat(vehicle, "getGunXRot", 0.0F);
        a.leftWheelRot = Reflect.getFloat(vehicle, "getLeftWheelRot", 0.0F);
        a.rightWheelRot = Reflect.getFloat(vehicle, "getRightWheelRot", 0.0F);
        a.leftTrack = Reflect.getFloat(vehicle, "getLeftTrack", 0.0F);
        a.rightTrack = Reflect.getFloat(vehicle, "getRightTrack", 0.0F);
        a.propellerRot = Reflect.getFloat(vehicle, "getPropellerRot", 0.0F);
        a.gearRot = Reflect.getFloat(vehicle, "getSynchedGearRot", 0.0F);

        a.cannonRecoilTime = Reflect.getInt(vehicle, "getCannonRecoilTime", 0);

        int flags = 0;
        if (Reflect.getBoolean(vehicle, "engineRunning", false)) flags |= 1;
        if (Reflect.getBoolean(vehicle, "getGearUp", false)) flags |= 2;
        if (Reflect.getBoolean(vehicle, "isFiring", false)) flags |= 4;
        if (Reflect.getBoolean(vehicle, "getHoverMode", false)) flags |= 8;
        if (Reflect.getBoolean(vehicle, "isWreck", false)) flags |= 16;
        a.flags = flags;

        return a;
    }

    /** 車体のロール角 (航空機用)。取得できない場合は 0。 */
    public static float readRoll(Entity vehicle) {
        return Reflect.getFloat(vehicle, "getRoll", 0.0F);
    }
}
