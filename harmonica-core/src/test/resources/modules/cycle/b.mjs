import { a } from "./a.mjs";
export const b = "B";
export let aSnapshotErrored = false;
try { void a; } catch (e) { aSnapshotErrored = true; }
