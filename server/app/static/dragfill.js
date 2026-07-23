(function () {
  var LONG_PRESS_MS = 450;
  var MOVE_SLOP = 10;
  var SYNTHETIC_MOUSE_WINDOW_MS = 500;

  var timer = null;
  var armed = false;
  var originCell = null;
  var originCellId = null;
  var rowCells = [];
  var originIndex = -1;
  var startX = 0, startY = 0;
  var lastTouchTime = 0;

  function isSyntheticMouseEvent() {
    return Date.now() - lastTouchTime < SYNTHETIC_MOUSE_WINDOW_MS;
  }

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

  function armPending(x, y, target) {
    var td = target.closest("td.cell");
    if (!td || !cellHasContent(td)) return;
    startX = x;
    startY = y;
    originCell = td;
    timer = setTimeout(arm, LONG_PRESS_MS);
  }

  function handleMove(x, y) {
    if (timer && !armed) {
      if (Math.abs(x - startX) > MOVE_SLOP || Math.abs(y - startY) > MOVE_SLOP) {
        clearTimeout(timer);
        timer = null;
      }
      return;
    }
    if (!armed) return;
    var rect = originCell.getBoundingClientRect();
    var originX = rect.left + rect.width / 2;
    var originY = rect.top + rect.height / 2;
    var clampedX = Math.max(x, originX);
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
  }

  function handleEnd() {
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
    // Filled cells are refreshed via the server's own cell.updated broadcast
    // (live.js), the same path every other mutation in this app uses -
    // doing it again here too would race that broadcast for the same DOM
    // element on an outerHTML swap.
    fetch("/web/cells/" + originIdForRequest + "/fill", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ target_cell_ids: targetIds })
    }).catch(function (err) { console.error("drag-fill request failed", err); });
  }

  // Touch input (tablets/touchscreens)
  document.addEventListener("touchstart", function (e) {
    lastTouchTime = Date.now();
    reset();
    if (e.touches.length !== 1) return;
    var touch = e.touches[0];
    armPending(touch.clientX, touch.clientY, e.target);
  }, { passive: true });

  document.addEventListener("touchmove", function (e) {
    lastTouchTime = Date.now();
    var touch = e.touches[0];
    if (!touch) return;
    handleMove(touch.clientX, touch.clientY);
  }, { passive: true });

  document.addEventListener("touchend", function () {
    lastTouchTime = Date.now();
    handleEnd();
  }, { passive: true });

  document.addEventListener("touchcancel", function () {
    lastTouchTime = Date.now();
    reset();
  }, { passive: true });

  // Mouse input (desktop browsers). Guarded against synthetic mouse events
  // that touchscreens fire shortly after real touch events, so the two
  // input paths don't double-trigger on hybrid devices.
  document.addEventListener("mousedown", function (e) {
    if (e.button !== 0 || isSyntheticMouseEvent()) return;
    reset();
    var td = e.target.closest("td.cell");
    if (!td || !cellHasContent(td)) return;
    e.preventDefault();
    armPending(e.clientX, e.clientY, td);
  });

  document.addEventListener("mousemove", function (e) {
    if (isSyntheticMouseEvent()) return;
    handleMove(e.clientX, e.clientY);
  });

  document.addEventListener("mouseup", function (e) {
    if (e.button !== 0 || isSyntheticMouseEvent()) return;
    handleEnd();
  });
})();
