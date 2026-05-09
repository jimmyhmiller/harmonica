import { a } from "./a.mjs";
import { b, aSnapshotErrored } from "./b.mjs";
export const result = { a, b, bGotTDZ: aSnapshotErrored };
