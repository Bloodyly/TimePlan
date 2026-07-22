(function () {
  var MIN_DISTANCE = 60;
  var MAX_DURATION = 600;
  var MIN_RATIO = 1.5;

  function detectSwipeDirection(dx, dy, durationMs) {
    if (durationMs > MAX_DURATION) return null;
    if (Math.abs(dx) < MIN_DISTANCE) return null;
    if (Math.abs(dx) <= Math.abs(dy) * MIN_RATIO) return null;
    return dx < 0 ? "next" : "prev";
  }

  function isDialogOpen() {
    var dialog = document.getElementById("cell-dialog");
    return !!(dialog && dialog.open);
  }

  function navigate(direction) {
    var link = document.querySelector(
      direction === "next" ? ".weeknav-next" : ".weeknav-prev"
    );
    if (!link) return;
    document.documentElement.classList.add("swipe-" + direction);
    sessionStorage.setItem("timeplan-swipe-dir", direction);
    location.href = link.href;
  }

  var startX = 0, startY = 0, startTime = 0, tracking = false;

  document.addEventListener("touchstart", function (e) {
    if (isDialogOpen() || e.touches.length !== 1) { tracking = false; return; }
    tracking = true;
    startX = e.touches[0].clientX;
    startY = e.touches[0].clientY;
    startTime = Date.now();
  }, { passive: true });

  document.addEventListener("touchend", function (e) {
    if (window.__timeplanGestureLock) { tracking = false; return; }
    if (!tracking) return;
    tracking = false;
    if (isDialogOpen()) return;
    var touch = e.changedTouches[0];
    var dx = touch.clientX - startX;
    var dy = touch.clientY - startY;
    var duration = Date.now() - startTime;
    var direction = detectSwipeDirection(dx, dy, duration);
    if (direction) navigate(direction);
  }, { passive: true });
})();
