import lib, { foo, bar } from "./lib.cjs";
export const result = { defaultFoo: lib.foo, named: foo + bar };
