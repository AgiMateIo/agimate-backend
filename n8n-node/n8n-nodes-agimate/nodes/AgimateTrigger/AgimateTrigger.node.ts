import type {
	IWebhookFunctions,
	INodeType,
	INodeTypeDescription,
	IWebhookResponseData,
} from 'n8n-workflow';
import { NodeConnectionTypes } from 'n8n-workflow';

export class AgimateTrigger implements INodeType {
	description: INodeTypeDescription = {
		displayName: 'Agimate Trigger',
		name: 'agimateTrigger',
		icon: { light: 'file:agimate-trigger.svg', dark: 'file:agimate-trigger.dark.svg' },
		group: ['trigger'],
		version: 1,
		description: 'Receives webhook events from Agimate platform',
		defaults: {
			name: 'Agimate Trigger',
		},
		inputs: [],
		outputs: [NodeConnectionTypes.Main],
		credentials: [
			{
				name: 'agimateApi',
				required: true,
			},
		],
		webhooks: [
			{
				name: 'default',
				httpMethod: 'POST',
				responseMode: 'onReceived',
				path: 'webhook',
			},
		],
		hints: [
			{
				message: "<h2>Manual registration required</h2> After activating this workflow, copy the webhook URL from above and register it on the Agimate website",
				type: 'warning',
				location: 'ndv',
				whenToDisplay: 'beforeExecution',
			}
		],
		properties: [
			{
				displayName: 'Event Types',
				name: 'eventTypes',
				type: 'string',
				default: '*',
				placeholder: 'mobile.trigger',
				description: 'Event types to filter. Use "*" for all events. Common types: mobile.trigger, connector.event, credential.updated, device.action',
				typeOptions: {
					multipleValues: true,
					multipleValueButtonText: 'Add Event Type',
				},
			},
			{
				displayName: 'Authentication',
				name: 'authentication',
				type: 'options',
				options: [
					{
						name: 'Header Token',
						value: 'header',
						description: 'Validate token from Authorization header',
					},
					{
						name: 'None',
						value: 'none',
						description: 'Accept all requests (not recommended for production)',
					},
				],
				default: 'header',
				description: 'How to authenticate incoming webhook requests',
			},
			{
				displayName: 'Include Headers',
				name: 'includeHeaders',
				type: 'boolean',
				default: false,
				description: 'Whether to include HTTP headers in the output data',
			},
			{
				displayName: 'Include Query Parameters',
				name: 'includeQuery',
				type: 'boolean',
				default: false,
				description: 'Whether to include query parameters in the output data',
			},
		],
	};

	async webhook(this: IWebhookFunctions): Promise<IWebhookResponseData> {
		const bodyData = this.getBodyData();
		const authMode = this.getNodeParameter('authentication') as string;
		const eventTypesFilter = this.getNodeParameter('eventTypes') as string[];
		const includeHeaders = this.getNodeParameter('includeHeaders') as boolean;
		const includeQuery = this.getNodeParameter('includeQuery') as boolean;

		// Authentication validation
		if (authMode === 'header') {
			const headers = this.getHeaderData();
			const authHeader = headers.authorization as string;

			if (!authHeader || !authHeader.startsWith('Bearer ')) {
				return {
					webhookResponse: {
						status: 'error',
						message: 'Missing or invalid Authorization header',
					},
				};
			}

			const token = authHeader.substring(7);
			const credentials = await this.getCredentials('agimateApi');

			if (token !== credentials.apiKey) {
				return {
					webhookResponse: {
						status: 'error',
						message: 'Invalid API key',
					},
				};
			}
		}

		// Event type filtering
		const eventType = (bodyData.eventType || bodyData.type || 'unknown') as string;

		if (!eventTypesFilter.includes('*') && !eventTypesFilter.includes(eventType)) {
			return {
				webhookResponse: {
					status: 'filtered',
					message: 'Event type not configured for this webhook',
					eventType,
				},
			};
		}

		// Build output data
		const outputData: any = {
			event: bodyData,
			metadata: {
				eventType,
				receivedAt: new Date().toISOString(),
				webhookName: this.getWebhookName(),
			},
		};

		if (includeHeaders) {
			outputData.headers = this.getHeaderData();
		}

		if (includeQuery) {
			outputData.query = this.getQueryData();
		}

		// Return success response
		return {
			workflowData: [[{ json: outputData }]],
			webhookResponse: {
				status: 'success',
				message: 'Event received',
				eventType,
			},
		};
	}
}
