package dev.antimony.gl.glsl;

/**
 * Thrown by generated program bodies on GLSL discard; the executor catches it
 * and leaves the render target's previous content (alpha 0) for that fragment.
 */
public class DiscardException extends RuntimeException {
    public DiscardException() {
        super("discard");
    }
}
