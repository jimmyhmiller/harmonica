import { x } from "./src.mjs";
let threw = false;
try { x = 5; } catch (e) { threw = e.name === "TypeError"; }
export const result = threw;
