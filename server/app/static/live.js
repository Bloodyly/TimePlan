(function () {
  function connect() {
    var proto = location.protocol === "https:" ? "wss" : "ws";
    var ws = new WebSocket(proto + "://" + location.host + "/ws/v1?client=web");
    ws.onmessage = function (msg) {
      var ev = JSON.parse(msg.data);
      if (ev.event === "cell.updated") {
        var el = document.getElementById("cell-" + ev.cell_id);
        if (el && !el.classList.contains("editing")) {
          htmx.ajax("GET", "/web/cells/" + ev.cell_id,
                    { target: el, swap: "outerHTML" });
        }
      } else if (ev.event === "workers.updated" || ev.event === "week.updated") {
        location.reload();
      }
    };
    ws.onclose = function () { setTimeout(connect, 3000); };
  }
  if (document.querySelector("table.grid")) { connect(); }
})();
