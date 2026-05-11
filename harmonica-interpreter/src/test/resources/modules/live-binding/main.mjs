import { count, bump } from "./counter.mjs";
const before = count;
bump(); bump(); bump();
const after = count;
let assignThrew = false;
try { count = 99; } catch (e) { assignThrew = e.name === "TypeError"; }
export const result = { before, after, assignThrew };
