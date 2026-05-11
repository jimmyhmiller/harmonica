const a = require("./a.cjs");
// At this point, a.aDone is still false because a's body didn't reach the end.
exports.bMid = "b-saw-aDone=" + a.aDone;
