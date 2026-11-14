"""Status pill decisions (Qt-free)."""
from flowclone.pill_state import PillController, PillModel


def run(model, ctl, events, show_pill=True):
    """Apply queued 'state' events the way the UI thread does; return the displays."""
    out = []
    for e in events:
        if e[0] == "state":
            _, state, message, flash_ms, seq = e
            out.append(model.on_request(state, message, flash_ms, seq, show_pill))
    events.clear()
    return out


def test_requested_state_is_visible_to_other_threads_immediately():
    events = []
    ctl = PillController(lambda *e: events.append(e))
    ctl.set_state("downloading", "42%")
    assert ctl.state == "downloading"            # before the UI applied it
    ctl.set_state("hands_free")
    assert ctl.state == "hands_free"


def test_flash_returns_to_the_sticky_download_pill():
    events, model = [], PillModel()
    ctl = PillController(lambda *e: events.append(e))
    ctl.set_state("downloading", "Downloading 10%")
    ctl.set_state("processing", "Speech model not ready yet", flash_ms=2500)
    shown = run(model, ctl, events)
    assert shown[-1].state == "processing" and shown[-1].flash_ms == 2500
    after = model.on_flash_done(show_pill=True)
    assert (after.visible, after.state, after.message) == (True, "downloading", "Downloading 10%")
    ctl.flash_finished(model.flash_seq, after.state)
    assert ctl.state == "downloading"


def test_flash_over_model_error_and_with_pill_disabled():
    events, model = [], PillModel()
    ctl = PillController(lambda *e: events.append(e))
    ctl.set_state("model_error", "Model download failed")
    ctl.set_state("processing", "Retry from the tray", flash_ms=2500)
    shown = run(model, ctl, events, show_pill=False)
    assert shown[0].visible is True               # errors are always shown
    assert shown[1].visible is False              # the flash itself respects the setting
    assert model.on_flash_done(show_pill=False).state == "model_error"


def test_stale_flash_does_not_override_newer_request():
    events, model = [], PillModel()
    ctl = PillController(lambda *e: events.append(e))
    ctl.set_state("processing", "No speech detected", flash_ms=1500)
    run(model, ctl, events)
    seq = model.flash_seq
    ctl.set_state("recording")                    # user starts talking again
    after = model.on_flash_done(show_pill=True)
    ctl.flash_finished(seq, after.state)
    assert ctl.state == "recording"


def test_normal_state_clears_sticky_and_hidden_is_invisible():
    model = PillModel()
    model.on_request("downloading", "x", 0, 1, True)
    assert model.on_request("hidden", None, 0, 2, True).visible is False
    assert model.sticky is None
    assert model.on_flash_done(True).state == "hidden"


def test_level_is_throttled():
    events, t = [], [0.0]
    ctl = PillController(lambda *e: events.append(e), clock=lambda: t[0])
    ctl.level(0.5)
    ctl.level(0.6)
    t[0] = 0.05
    ctl.level(0.7)
    assert [e[1] for e in events] == [0.5, 0.7]
