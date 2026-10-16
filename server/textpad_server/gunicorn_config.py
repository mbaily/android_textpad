"""Create shared commit signals before Gunicorn forks its workers."""
preload_app = True


def changes(server):
    return server.app.wsgi().extensions["changes"]


def on_starting(server):
    changes(server).prepare_workers(server.cfg.workers)


def pre_fork(server, worker):
    used = {getattr(sibling, "textpad_event_slot", None) for sibling in server.WORKERS.values()}
    worker.textpad_event_slot = next(slot for slot in range(len(changes(server).channels)) if slot not in used)


def post_worker_init(worker):
    worker.wsgi.extensions["changes"].start_worker(worker.textpad_event_slot)


def worker_exit(server, worker):
    changes(server).stop_worker()


def child_exit(server, worker):
    # Also covers a worker dying after COMMIT but before it publishes its signal.
    changes(server).publish()


def on_exit(server):
    changes(server).close()
