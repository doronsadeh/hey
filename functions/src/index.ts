import * as functions from 'firebase-functions';
import * as admin from 'firebase-admin';

admin.initializeApp(functions.config().firebase);

export const msg = functions.https.onRequest((request, response) => {
    //
    // Message Proxy
    //
    console.log(JSON.stringify(request.query));

    const _qp: any = request.query.tokens;
    const registrationTokens = _qp.split(',');

    console.log(JSON.stringify(registrationTokens));

    const mtype: any = request.query.mt;
    const text: any = request.query.text;
    const ts: any = request.query.ts;
    const hailing_rider_id: any = request.query.sid;
    const loc: any = request.query.loc;
    const rids: any = request.query.rids;

    const message = {
        data: {
            msg_type: mtype,
            time: ts,
            msg_text: text,
            loc: loc,
            hailing_rider_id: hailing_rider_id,
            rids: rids
        },
        tokens: registrationTokens
    };

    admin.messaging().sendMulticast(message)
        .then((_response) => {
            console.log(`Multicast response: ${JSON.stringify(_response)}`);
            if (_response.failureCount > 0) {
                const failedTokens: string[] = [];
                _response.responses.forEach((resp, idx) => {
                    if (!resp.success) {
                        console.log(`Failed tokens: ${JSON.stringify(resp)}`);
                        failedTokens.push(registrationTokens[idx]);
                    }
                });

                console.log(`List of tokens that caused failures: ${failedTokens}`);

                // Return the number of successful sends
                response.send({success: registrationTokens.length - failedTokens.length});
            } else {
                response.send({success: registrationTokens.length});
            }
        })
        .catch(() => {
            console.log('Failed sending multicast');
        });
});
