(function () {
  var LONG_PRESS_MS = 450;
  var MOVE_SLOP = 10;

  var timer = null;
  var armed = false;
  var originCell = null;
  var originCellId = null;
  var rowCells = [];
  var originIndex = -1;
  var startX = 0, startY = 0;

  function cellHasContent(td) {
    return td.textContent.trim().length > 0;
  }

  function updateArrow(x1, y, x2) {
    var line = document.getElementById("dragfill-arrow-line");
    var head = document.getElementById("dragfill-arrow-head");
    line.setAttribute("x1", x1); line.setAttribute("y1", y);
    line.setAttribute("x2", x2); line.setAttribute("y2", y);
    var headSize = 10;
    var hx = x2 - headSize;
    head.setAttribute("points",
      x2 + "," + y + " " + hx + "," + (y - headSize / 2) + " " + hx + "," + (y + headSize / 2));
  }

  function showOverlay() { document.getElementById("dragfill-arrow-overlay").style.display = "block"; }
  function hideOverlay() { document.getElementById("dragfill-arrow-overlay").style.display = "none"; }

  function clearMarks() {
    rowCells.forEach(function (td) { td.classList.remove("dragfill-marked"); });
  }

  function reset() {
    if (timer) { clearTimeout(timer); timer = null; }
    if (armed) { clearMarks(); hideOverlay(); window.__timeplanGestureLock = false; }
    armed = false;
    originCell = null;
    rowCells = [];
    originIndex = -1;
  }

  function arm() {
    if (!originCell) return;
    armed = true;
    window.__timeplanGestureLock = true;
    originCellId = originCell.id.replace(/^cell-/, "");
    rowCells = Array.prototype.slice.call(originCell.parentElement.querySelectorAll("td.cell"));
    originIndex = rowCells.indexOf(originCell);
    var rect = originCell.getBoundingClientRect();
    var originX = rect.left + rect.width / 2;
    var originY = rect.top + rect.height / 2;
    showOverlay();
    updateArrow(originX, originY, originX);
  }

  document.addEventListener("touchstart", function (e) {
    reset();
    var td = e.target.closest("td.cell");
    if (!td || !cellHasContent(td) || e.touches.length !== 1) return;
    var touch = e.touches[0];
    startX = touch.clientX;
    startY = touch.clientY;
    originCell = td;
    timer = setTimeout(arm, LONG_PRESS_MS);
  }, { passive: true });

  document.addEventListener("touchmove", function (e) {
    if (timer && !armed) {
      var touch = e.touches[0];
      if (Math.abs(touch.clientX - startX) > MOVE_SLOP || Math.abs(touch.clientY - startY) > MOVE_SLOP) {
        clearTimeout(timer);
        timer = null;
      }
      return;
    }
    if (!armed) return;
    var touch = e.touches[0];
    var rect = originCell.getBoundingClientRect();
    var originX = rect.left + rect.width / 2;
    var originY = rect.top + rect.height / 2;
    var clampedX = Math.max(touch.clientX, originX);
    updateArrow(originX, originY, clampedX);

    for (var i = originIndex + 1; i < rowCells.length; i++) {
      var cellRect = rowCells[i].getBoundingClientRect();
      var cellCenterX = cellRect.left + cellRect.width / 2;
      if (clampedX >= cellCenterX) {
        rowCells[i].classList.add("dragfill-marked");
      } else {
        rowCells[i].classList.remove("dragfill-marked");
      }
    }
  }, { passive: true });

  document.addEventListener("touchend", function () {
    if (!armed) { reset(); return; }
    var targetIds = [];
    for (var i = originIndex + 1; i < rowCells.length; i++) {
      if (rowCells[i].classList.contains("dragfill-marked")) {
        targetIds.push(rowCells[i].id.replace(/^cell-/, ""));
      }
    }
    var originIdForRequest = originCellId;
    reset();
    if (targetIds.length === 0) return;
    fetch("/web/cells/" + originIdForRequest + "/fill", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ target_cell_ids: targetIds })
    }).then(function (r) { return r.json(); }).then(function (data) {
      (data.filled || []).forEach(function (cellId) {
        var el = document.getElementById("cell-" + cellId);
        if (el) htmx.ajax("GET", "/web/cells/" + cellId, { target: el, swap: "outerHTML" });
      });
    }).catch(function (err) { console.error("drag-fill request failed", err); });
  }, { passive: true });

  document.addEventListener("touchcancel", reset, { passive: true });
})();
