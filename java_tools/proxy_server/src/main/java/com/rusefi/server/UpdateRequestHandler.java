package com.rusefi.server;

import com.devexperts.logging.Logging;
import com.rusefi.proxy.NetworkConnector;
import com.rusefi.proxy.client.UpdateType;
import com.rusefi.tools.online.ProxyClient;
import org.takes.Request;
import org.takes.Response;
import org.takes.Take;
import org.takes.rq.RqForm;
import org.takes.rq.form.RqFormBase;
import org.takes.rs.RsJson;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import java.io.IOException;

import static com.devexperts.logging.Logging.getLogging;

public class UpdateRequestHandler implements Take {
    private static final Logging log = getLogging(UpdateRequestHandler.class);
    private final Backend backend;

    public UpdateRequestHandler(Backend backend) {
        this.backend = backend;
    }

    @Override
    public Response act(Request req) throws IOException {

        JsonObjectBuilder objectBuilder = Json.createObjectBuilder();

        try {
            RqForm rqForm = new RqFormBase(req);

            String json = rqForm.param(ProxyClient.JSON).iterator().next();
            String type = rqForm.param(ProxyClient.UPDATE_TYPE).iterator().next();

            ApplicationRequest applicationRequest = ApplicationRequest.valueOf(json);
            UserDetails tuner = backend.getUserDetailsResolver().apply(applicationRequest.getSessionDetails().getAuthToken());

            ControllerKey key = new ControllerKey(applicationRequest.getVehicleOwner().getUserId(), applicationRequest.getSessionDetails().getControllerInfo());
            log.info("Online Request for " + key + ": " + type);

            ControllerConnectionState state = backend.acquire(key, tuner);
            if (state == null)
                throw new IOException("Not acquired " + tuner);

            // should controller communication happen on http thread or not?
            new Thread(() -> {
                try {
                    if (type.equals(UpdateType.FIRMWARE.name())) {
                        state.invokeOnlineCommand(NetworkConnector.UPDATE_FIRMWARE);
                    } else if (type.equals(UpdateType.CONTROLLER_RELEASE.name())) {
                        state.invokeOnlineCommand(NetworkConnector.UPDATE_FIRMWARE);
                    } else {
                        state.invokeOnlineCommand(NetworkConnector.UPDATE_CONNECTOR_SOFTWARE_LATEST);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    state.getTwoKindSemaphore().releaseFromLongTermUsage();
                }
            }).start();

            log.debug("Update request " + tuner);
        } catch (IOException e) {
            objectBuilder.add("result", "error: " + e);
            return new RsJson(objectBuilder.build());
        }

        objectBuilder.add("result", "OK");
        return new RsJson(objectBuilder.build());
    }
}
