package ddraig.net.azureframelib.model;

/**
 * Size of a model's "Hitbox" bone, in blocks (model units divided by 16).
 *
 * @param width  size along X
 * @param height size along Y
 * @param depth  size along Z
 */
public record HitboxSize(float width, float height, float depth) {
}
