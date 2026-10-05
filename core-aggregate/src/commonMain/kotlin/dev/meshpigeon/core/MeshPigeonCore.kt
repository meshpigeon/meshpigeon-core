package dev.meshpigeon.core

/**
 * The umbrella artifact: `dev.meshpigeon:meshpigeon-core`.
 *
 * Clients depend on this one coordinate and get every module they are allowed
 * to see through it. What it deliberately does *not* carry is as important as
 * what it does — `meshpigeon-core-testing` is not here, so a published client
 * cannot ship the fakes by accident (tests depend on that coordinate directly
 * instead).
 *
 * There is no behaviour here, and there is meant to be none: this object
 * exists so every target has a real artifact to publish under the coordinate
 * that names the library, and so `import dev.meshpigeon.core.MeshPigeonCore` is
 * the one symbol that says "this client is on meshpigeon-core".
 */
public object MeshPigeonCore