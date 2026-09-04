package dev.antiboatfly;

import java.util.Locale;

/**
 * A small, independent simulation for the next airborne boat movement packet.
 * Horizontal input is represented by a tolerance envelope, while vertical motion
 * must follow gravity because normal boat controls cannot add vertical thrust.
 */
final class BoatSimulation {
    private final double gravity;
    private final double horizontalDrag;
    private final double verticalTolerance;
    private final double horizontalTolerance;

    BoatSimulation(double gravity, double horizontalDrag,
                   double verticalTolerance, double horizontalTolerance) {
        this.gravity = gravity;
        this.horizontalDrag = horizontalDrag;
        this.verticalTolerance = verticalTolerance;
        this.horizontalTolerance = horizontalTolerance;
    }

    Result validate(double serverX, double serverY, double serverZ,
                    double velocityX, double velocityY, double velocityZ,
                    double packetX, double packetY, double packetZ, int elapsedTicks) {
        double predictedVelocityX = velocityX;
        double predictedVelocityY = velocityY;
        double predictedVelocityZ = velocityZ;
        double predictedX = serverX;
        double predictedY = serverY;
        double predictedZ = serverZ;

        for (int tick = 0; tick < elapsedTicks; tick++) {
            predictedVelocityX *= horizontalDrag;
            predictedVelocityY -= gravity;
            predictedVelocityZ *= horizontalDrag;
            predictedX += predictedVelocityX;
            predictedY += predictedVelocityY;
            predictedZ += predictedVelocityZ;
        }

        // Only movement above the prediction grants a flight advantage. Extra
        // downward movement can come from collisions, corrections, or packet timing.
        double verticalOffset = packetY - predictedY;
        double horizontalOffset = Math.hypot(packetX - predictedX, packetZ - predictedZ);

        double scaledVerticalTolerance = verticalTolerance * Math.sqrt(elapsedTicks);
        double scaledHorizontalTolerance = horizontalTolerance * elapsedTicks;
        if (verticalOffset > scaledVerticalTolerance) {
            return new Result(false, "boat-simulation-vertical(offset="
                    + format(verticalOffset) + ")", verticalOffset, horizontalOffset,
                    predictedVelocityX, predictedVelocityY, predictedVelocityZ);
        }
        if (horizontalOffset > scaledHorizontalTolerance) {
            return new Result(false, "boat-simulation-horizontal(offset="
                    + format(horizontalOffset) + ")", verticalOffset, horizontalOffset,
                    predictedVelocityX, predictedVelocityY, predictedVelocityZ);
        }
        return new Result(true, "valid", verticalOffset, horizontalOffset,
                predictedVelocityX, predictedVelocityY, predictedVelocityZ);
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    record Result(boolean valid, String reason, double verticalOffset, double horizontalOffset,
                  double predictedVelocityX, double predictedVelocityY, double predictedVelocityZ) {
    }
}
