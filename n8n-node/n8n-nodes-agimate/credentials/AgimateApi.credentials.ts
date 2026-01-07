import type {IAuthenticateGeneric, Icon, ICredentialType, INodeProperties,} from 'n8n-workflow';

export class AgimateApi implements ICredentialType {
    name = 'agimateApi';
    displayName = 'Agimate API';
    documentationUrl = 'https://agimate.io/docs';
    icon: Icon = { light: 'file:../icons/logo.svg', dark: 'file:../icons/logo.svg'};

    properties: INodeProperties[] = [
        {
            displayName: 'API URL',
            name: 'apiUrl',
            type: 'string',
            default: 'https://api.agimate.io',
            required: true,
            description: 'Base URL of the Agimate API',
        },
        {
            displayName: 'API Key',
            name: 'apiKey',
            type: 'string',
            typeOptions: {password: true},
            default: '',
            required: true,
        },
    ];

    authenticate: IAuthenticateGeneric = {
        type: 'generic',
        properties: {
            headers: {
                Authorization: '={{"Bearer " + $credentials.apiKey}}',
            },
        },
    };
}
